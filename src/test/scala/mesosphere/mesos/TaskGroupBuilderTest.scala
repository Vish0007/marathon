package mesosphere.mesos

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{MarathonSpec, MarathonTestHelper}
import org.scalatest.Matchers

class TaskGroupBuilderTest extends MarathonSpec with Matchers {
  test("Foo") {
    val offer = MarathonTestHelper.makeBasicOffer().build

    val pod = TaskGroupBuilder.build(
      PodDefinition(
        id = "/product/frontend".toPath
      ),
      offer,
      s => Instance.Id(s.toString),
      MarathonTestHelper.defaultConfig(
        mesosRole = None,
        acceptedResourceRoles = None,
        envVarsPrefix = None),
      None)

    assert(pod.isDefined)
  }

}