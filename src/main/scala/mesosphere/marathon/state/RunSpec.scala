package mesosphere.marathon.state

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

/**
  * A generic spec that specifies something that Marathon is able to launch instances of.
  */

// TODO(PODS): Group some of this into little types and pattern match when things really
// don't make sense to do generically, eg 'executor', 'cmd', 'args', etc.
// we should try to group things up logically - pod does a decent job of this
trait RunSpec extends plugin.RunSpec {
  val id: PathId
  val env: Map[String, EnvVarValue]
  val labels: Map[String, String]
  val acceptedResourceRoles: Set[String]
  val secrets: Map[String, Secret]

  val instances: Int
  val constraints: Set[Constraint]

  val version: Timestamp

  // TODO: these could go into a resources object
  val cpus: Double
  val mem: Double
  val disk: Double
  val gpus: Int

  // TODO: Group into backoff?
  val backoff: FiniteDuration
  val maxLaunchDelay: FiniteDuration
  val backoffFactor: Double

  val residency: Option[Residency]
  val healthChecks: Set[HealthCheck]
  val readinessChecks: Seq[ReadinessCheck]
  val upgradeStrategy: UpgradeStrategy
  def portAssignments(task: Task): Seq[PortAssignment]
  val taskKillGracePeriod = Option.empty[FiniteDuration]

  def withInstances(instances: Int): RunSpec
  def isUpgrade(to: RunSpec): Boolean
  def needsRestart(to: RunSpec): Boolean
  def isOnlyScaleChange(to: RunSpec): Boolean
  val versionInfo: VersionInfo

  // TODO(PODS)- do pods support this anyways?
  val ipAddress: Option[IpAddress]

  // TODO: These ones probably should only exist in app and we should be pattern matching
  val requirePorts: Boolean = false
  val portNumbers = Seq.empty[Int]
  val container = Option.empty[Container]
  val executor: String = ""
  val cmd = Option.empty[String]
  val args = Seq.empty[String]
  val isSingleInstance: Boolean = false
  val volumes = Seq.empty[Volume]
  val persistentVolumes = Seq.empty[PersistentVolume]
  val externalVolumes = Seq.empty[ExternalVolume]
  val diskForPersistentVolumes: Double = 0.0
  val portDefinitions = Seq.empty[PortDefinition]
  // TODO(
  val fetch = Seq.empty[FetchUri]
  val portNames = Seq.empty[String]
}
