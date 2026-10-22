package ai.starlake.quack

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class NodeLeaseConfigSpec extends AnyFunSpec with Matchers:
  import Main.given

  private def load(overlay: String): ManagerConfig =
    ConfigSource
      .string(overlay)
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  it("defaults quack-on-demand.nodeLeaseSec to 30"):
    load("").nodeLeaseSec shouldBe 30

  it("reads a configured lease"):
    load("quack-on-demand.nodeLeaseSec = 3").nodeLeaseSec shouldBe 3

  it("refuses a lease below one second, naming the env var"):
    val e = intercept[IllegalArgumentException](load("quack-on-demand.nodeLeaseSec = 0"))
    e.getMessage should include("QOD_QUACK_NODE_LEASE_SEC")
