package mesosphere.mesos

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.raml
import mesosphere.marathon.state.{ EnvVarString, PathId, Timestamp }
import org.apache.mesos.{ Protos => mesos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object TaskGroupBuilder {
  def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newTaskId: PathId => Instance.Id,
    config: MarathonConf,
    resourceMatchOpt: Option[ResourceMatcher.ResourceMatch],
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch] = None): Option[(mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]])] = {

    resourceMatchOpt match {
      case Some(resourceMatch) =>
        build(podDefinition, offer, newTaskId, config, resourceMatch, volumeMatchOpt)
      case _ =>
        None
    }
  }

  private[this] def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newTaskId: PathId => Instance.Id,
    config: MarathonConf,
    resourceMatch: ResourceMatcher.ResourceMatch,
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch]): Some[(mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]])] = {
    val host: Option[String] = Some(offer.getHostname)

    // TODO: probably set unique ID for each task
    val taskId = newTaskId(podDefinition.id)

    val executorInfo = computeExecutorInfo(podDefinition.labels, taskId)

    val taskGroup = mesos.TaskGroupInfo.newBuilder

    podDefinition.containers.map { container =>
      val builder = mesos.TaskInfo.newBuilder
        .setName(container.name)
        .setTaskId(taskId.mesosTaskId)
        .setSlaveId(offer.getSlaveId)

      def scalarResource(name: String, value: Double) = {
        mesos.Resource.newBuilder
          .setName(name)
          .setType(mesos.Value.Type.SCALAR)
          .setScalar(mesos.Value.Scalar.newBuilder.setValue(value))
      }

      // TODO: set resources
      builder.addResources(scalarResource("cpus", container.resources.cpus.toDouble))
      builder.addResources(scalarResource("mem", container.resources.mem.toDouble))

      container.resources.gpus.foreach(gpu => builder.addResources(scalarResource("gpus", gpu.toDouble)))

      volumeMatchOpt.foreach(_.persistentVolumeResources.foreach(builder.addResources(_)))

      val labels = podDefinition.labels ++ container.labels.map(_.values).getOrElse(Map.empty[String, String])

      if (labels.nonEmpty)
        builder.setLabels(mesos.Labels.newBuilder.addAllLabels(labels.map {
          case (key, value) =>
            mesos.Label.newBuilder.setKey(key).setValue(value).build
        }.asJava))

      val envPrefix: Option[String] = config.envVarsPrefix.get

      val commandInfo = computeCommandInfo(podDefinition, taskId, container, host, resourceMatch.hostPorts, envPrefix)

      builder.setCommand(commandInfo)

      val containerInfo = computeContainerInfo(podDefinition.volumes, container)

      builder.setContainer(containerInfo)

      container.healthCheck.foreach { healthCheck =>
        builder.setHealthCheck(computeHealthCheck(healthCheck))
      }

      builder
    } foreach (taskGroup.addTasks)

    Some((executorInfo.build, taskGroup.build, resourceMatch.hostPorts))
  }

  private[this] def computeExecutorInfo(
    labels: Map[String, String],
    taskId: Instance.Id): mesos.ExecutorInfo.Builder = {
    val executorID = mesos.ExecutorID.newBuilder.setValue(f"marathon-${taskId.idString}")

    val executorInfo = mesos.ExecutorInfo.newBuilder
      .setExecutorId(executorID)

    // TODO: Set resources

    if (labels.nonEmpty) {
      mesos.Labels.newBuilder
        .addAllLabels(labels.map {
          case (key, value) =>
            mesos.Label.newBuilder.setKey(key).setValue(value).build
        }.asJava)
    }

    executorInfo
  }

  private[this] def computeCommandInfo(
    podDefinition: PodDefinition,
    taskId: Instance.Id,
    container: raml.MesosContainer,
    host: Option[String],
    hostPorts: Seq[Option[Int]],
    envPrefix: Option[String]): mesos.CommandInfo.Builder = {
    val commandInfo = mesos.CommandInfo.newBuilder

    // Container user overrides pod user
    val user = container.user.orElse(podDefinition.user)
    user.foreach(commandInfo.setUser(_))

    val uris = container.artifacts.map { artifact =>
      val uri = mesos.CommandInfo.URI.newBuilder.setValue(artifact.uri)

      artifact.cache.foreach(uri.setCache)
      artifact.extract.foreach(uri.setExtract)
      artifact.executable.foreach(uri.setExecutable)

      uri.build
    }

    commandInfo.addAllUris(uris.asJava)

    val podEnvVars = podDefinition.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val taskEnvVars = container.environment
      .map(_.values)
      .getOrElse(Map.empty[String, raml.EnvVarValueOrSecret])
      .collect{ case (k: String, v: raml.EnvVarValue) => k -> v.value }

    val hostEnvVar = host.map("HOST" -> _).toMap

    val taskContextEnvVars = taskContextEnv(container, podDefinition.version, taskId)

    val podlabelsEnvVars = labelsToEnvVars(podDefinition.labels)

    val taskLabelsEnvVars = labelsToEnvVars(container.labels.map(_.values).getOrElse(Map.empty[String, String]))

    val envVars = (podEnvVars ++
      taskEnvVars ++
      hostEnvVar ++
      taskContextEnvVars ++
      podlabelsEnvVars ++
      taskLabelsEnvVars)
      .map {
        case (name, value) =>
          mesos.Environment.Variable.newBuilder.setName(name).setValue(value).build
      }

    commandInfo.setEnvironment(mesos.Environment.newBuilder.addAllVariables(envVars.asJava))

    commandInfo
  }

  private[this] def computeContainerInfo(
    hostVolumes: Seq[raml.Volume],
    container: raml.MesosContainer): mesos.ContainerInfo.Builder = {
    val containerInfo = mesos.ContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.MESOS)

    container.volumeMounts.foreach { volumeMount =>
      hostVolumes.find(_.name == volumeMount.name).map { hostVolume =>
        val volume = mesos.Volume.newBuilder
          .setContainerPath(volumeMount.mountPath)

        hostVolume.host.foreach(volume.setHostPath)
        volumeMount.readOnly.foreach {
          case true =>
            volume.setMode(mesos.Volume.Mode.RO)
          case false =>
            volume.setMode(mesos.Volume.Mode.RW)
        }

        containerInfo.addVolumes(volume)
      }
    }

    container.image.foreach(im => im.kind match {
      case raml.ImageType.Docker =>
        val docker = mesos.Image.Docker.newBuilder
          .setName(im.id)

        val image = mesos.Image.newBuilder
          .setType(mesos.Image.Type.DOCKER)
          .setDocker(docker)
          .setCached(!im.forcePull.getOrElse(true))
        val mesosInfo = mesos.ContainerInfo.MesosInfo.newBuilder
          .setImage(image)

        containerInfo
          .setMesos(mesosInfo)
      case raml.ImageType.Appc =>
        val appc = mesos.Image.Appc.newBuilder
          .setName(im.id)

        val image = mesos.Image.newBuilder
          .setType(mesos.Image.Type.APPC)
          .setAppc(appc)
          .setCached(!im.forcePull.getOrElse(true))
        val mesosInfo = mesos.ContainerInfo.MesosInfo.newBuilder
          .setImage(image)

        containerInfo
          .setMesos(mesosInfo)
    })

    containerInfo
  }

  private[this] def computeHealthCheck(healthCheck: raml.HealthCheck): mesos.HealthCheck.Builder = {
    val builder = mesos.HealthCheck.newBuilder

    if (healthCheck.command.isDefined) {
      builder.setType(mesos.HealthCheck.Type.COMMAND)

      val command = mesos.CommandInfo.newBuilder

      healthCheck.command.get.command match {
        case raml.ShellCommand(shell) =>
          command.setShell(true)
          command.setValue(shell)
        case raml.ArgvCommand(argv) =>
          command.setShell(false)
          command.addAllArguments(argv.asJava)
      }

      builder.setCommand(command)
    } else if (healthCheck.http.isDefined) {
      builder.setType(mesos.HealthCheck.Type.HTTP)

      val http = mesos.HealthCheck.HTTPCheckInfo.newBuilder

      healthCheck.http.get.scheme.foreach(scheme => http.setScheme(scheme.value))
      healthCheck.http.get.path.foreach(path => http.setPath(path))

      builder.setHttp(http)
    } else if (healthCheck.tcp.isDefined) {
      builder.setType(mesos.HealthCheck.Type.TCP)

      val tcp = mesos.HealthCheck.TCPCheckInfo.newBuilder

      builder.setTcp(tcp)
    }

    builder
  }

  private[this] def taskContextEnv(
    container: raml.MesosContainer,
    version: Timestamp,
    taskId: Instance.Id): Map[String, String] = {
    Map(
      "MESOS_TASK_ID" -> Some(taskId.idString),
      "MARATHON_APP_ID" -> Some(container.name),
      "MARATHON_APP_VERSION" -> Some(version.toString),
      "MARATHON_APP_RESOURCE_CPUS" -> Some(container.resources.cpus.toString),
      "MARATHON_APP_RESOURCE_MEM" -> Some(container.resources.mem.toString),
      "MARATHON_APP_RESOURCE_DISK" -> Some(container.resources.disk.toString),
      "MARATHON_APP_RESOURCE_GPUS" -> Some(container.resources.gpus.toString)
    ).collect {
        case (key, Some(value)) => key -> value
      }
  }

  val maxEnvironmentVarLength = 512
  val labelEnvironmentKeyPrefix = "MARATHON_APP_LABEL_"
  val maxVariableLength = maxEnvironmentVarLength - labelEnvironmentKeyPrefix.length

  private[this] def labelsToEnvVars(labels: Map[String, String]): Map[String, String] = {
    def escape(name: String): String = name.replaceAll("[^a-zA-Z0-9_]+", "_").toUpperCase

    val validLabels = labels.collect {
      case (key, value) if key.length < maxVariableLength
        && value.length < maxEnvironmentVarLength => escape(key) -> value
    }

    val names = Map("MARATHON_APP_LABELS" -> validLabels.keys.mkString(" "))
    val values = validLabels.map { case (key, value) => s"$labelEnvironmentKeyPrefix$key" -> value }
    names ++ values
  }
}
