package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalQuackBackendPathSpec extends AnyFlatSpec with Matchers:

  "duckdbPathPrefix" should "resolve under QOD_APP_HOME when set" in {
    LocalQuackBackend.duckdbPathPrefix(Some("/opt/qod")) shouldBe Some("/opt/qod/duckdb/bin")
  }

  it should "be None when QOD_APP_HOME is unset (dev/source path unaffected)" in {
    LocalQuackBackend.duckdbPathPrefix(None) shouldBe None
  }

  // The stop paths (stop-jar, kill-quack-nodes, qod stop) match on `<rest-port>-<hex>`; the spawn
  // scripts refuse anything else.
  "managerMarker" should "be the REST port and a hex boot id, stable for the process" in {
    val m = LocalQuackBackend.managerMarker(20960)
    m should fullyMatch regex "20960-[0-9a-f]{8}"
    LocalQuackBackend.managerMarker(20960) shouldBe m
  }

  "defaultCommand" should "pass the manager marker as the spawn script's second argument" in {
    val spec = ai.starlake.quack.model.NodeSpec(
      ai.starlake.quack.model.PoolKey("t", "t_db", "p"),
      "n1",
      ai.starlake.quack.model.Role.Dual,
      Map.empty,
      Map.empty
    )
    val cmd = LocalQuackBackend.defaultCommand("spawn.sh", "spawn.ps1", "20960-0a1b2c3d")(
      spec,
      23300,
      "tok"
    )
    cmd.takeRight(2) shouldBe List("23300", "20960-0a1b2c3d")
  }
