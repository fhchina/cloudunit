package fr.treeptik.cloudunit.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.treeptik.cloudunit.config.events.ApplicationPendingEvent;
import fr.treeptik.cloudunit.config.events.ApplicationStartEvent;
import fr.treeptik.cloudunit.config.events.ServerStartEvent;
import fr.treeptik.cloudunit.config.events.ServerStopEvent;
import fr.treeptik.cloudunit.dao.VolumeDAO;
import fr.treeptik.cloudunit.docker.core.DockerCloudUnitClient;
import fr.treeptik.cloudunit.exception.CheckException;
import fr.treeptik.cloudunit.exception.ServiceException;
import fr.treeptik.cloudunit.model.Application;
import fr.treeptik.cloudunit.model.Server;
import fr.treeptik.cloudunit.model.Volume;
import fr.treeptik.cloudunit.service.DockerService;
import fr.treeptik.cloudunit.service.ServerService;
import fr.treeptik.cloudunit.service.VolumeService;

@Service
public class VolumeServiceImpl implements VolumeService {

	@Inject
	private VolumeDAO volumeDAO;

	@Inject
	private DockerCloudUnitClient dockerCloudUnitClient;

	@Inject
	private DockerService dockerService;

	@Inject
	private ServerService serverService;

	@Inject
	private ApplicationEventPublisher publisher;

	@Override
	@Transactional
	public void createNewVolume(Volume volume, Application application, String containerName)
			throws ServiceException, CheckException {
		if (volume.getName() == null || volume.getName().isEmpty())
			throw new CheckException("This name is not consistent !");
		if (volume.getPath() == null || volume.getPath().isEmpty())
			throw new CheckException("This path is not consistent !");
		if (!volume.getName().matches("^[-a-zA-Z0-9_]*$"))
			throw new CheckException("This name is not consistent : " + volume.getName());
		if (loadAllVolumes().stream().filter(v -> v.getName().equals(volume.getName())).findAny().isPresent()) {
			throw new CheckException("This name already exists");
		}
		createVolumeAndLaunchContainer(volume, containerName, application, false);
	}

	@Override
	@Transactional
	public void updateVolume(Volume volume, Application application, String containerName)
			throws ServiceException, CheckException {

		if (volume.getName() == null || volume.getName().isEmpty())
			throw new CheckException("This name is not consistent !");
		if (volume.getPath() == null || volume.getPath().isEmpty())
			throw new CheckException("This path is not consistent !");
		if (!volume.getName().matches("^[-a-zA-Z0-9_]*$"))
			throw new CheckException("This name is not consistent : " + volume.getName());
		if (loadAllVolumes().stream().filter(v -> v.getName().equals(volume.getName())).findAny().isPresent()) {
			throw new CheckException("This name already exists");
		}
		Volume currentVolume = loadVolume(volume.getId());
		if (currentVolume.getName().equals(volume.getName()) && currentVolume.getPath().equals(volume.getPath())) {
			throw new CheckException("The volume does not change");
		}
		dockerCloudUnitClient.removeVolume(volume.getName());
		createVolumeAndLaunchContainer(volume, containerName, application, false);

	}

	@Override
	public Volume loadVolume(int id) throws CheckException {
		Volume volume = volumeDAO.findById(id);
		if (volume.equals(null))
			throw new CheckException("Volume doesn't exist");
		return volume;
	}

	@Override
	public List<Volume> loadVolumeByApplication(String applicationName) {
		return volumeDAO.findByApplicationName(applicationName);
	}

	@Override
	public List<Volume> loadVolumeByContainer(String containerName) {
		return volumeDAO.findByContainer(containerName);
	}

	@Override
	public List<Volume> loadAllVolumes() {
		return volumeDAO.findAllVolumes();
	}

	@Override
	@Transactional
	public void delete(int id) throws CheckException, ServiceException {
		Volume volume = loadVolume(id);
		dockerCloudUnitClient.removeVolume(volume.getName());
		volumeDAO.delete(id);
		createVolumeAndLaunchContainer(volume, volume.getContainerName(),
				serverService.findByName(volume.getContainerName()).getApplication(), true);
	}

	private void createVolumeAndLaunchContainer(Volume volume, String containerName, Application application,
			boolean afterRemove) throws ServiceException {
		Server server = serverService.findByName(containerName);
		publisher.publishEvent(new ApplicationPendingEvent(application));

		if (!afterRemove) {
			volume.setApplication(application);
			volume.setContainerName(containerName);
			dockerCloudUnitClient.createVolume(volume.getName(), "runtime");
			volumeDAO.save(volume);
		}

		publisher.publishEvent(new ServerStopEvent(server));
		dockerService.removeServer(server.getName(), false);
		List<String> volumes = loadVolumeByContainer(server.getName()).stream()
				.map(t -> t.getName() + ":" + t.getPath() + ":rw").collect(Collectors.toList());
		dockerService.createServer(server.getName(), server, server.getImage().getPath(),
				server.getApplication().getUser(), null, false, volumes);
		server = serverService.startServer(server);
		serverService.addCredentialsForServerManagement(server, server.getApplication().getUser());
		publisher.publishEvent(new ServerStartEvent(server));
		publisher.publishEvent(new ApplicationStartEvent(application));
	}

}
