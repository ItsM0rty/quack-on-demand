package ai.starlake.quack.edge.adapter

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class QuackNativeBridgeSpec extends AnyFunSpec with Matchers:
  describe("QuackNativeBridge"):
    it("loads libquackwire and returns 42") {
      QuackNativeBridge.smokeAnswer() shouldBe 42
    }

    it("reports a build marker for the Quack protocol and DuckDB release this jar pins") {
      val fields = NativeBuildInfo.fields(QuackNativeBridge.buildInfo())
      fields.get("protocol") shouldBe Some(NativeBuildInfo.ProtocolVersion.toString)
      val abi = LibDuckDbPreload.bundledAbi.getOrElse(fail("no /native/VERSION stamp"))
      fields.get("duckdb").map(_.stripPrefix("v")).getOrElse("") should startWith(abi)
    }

    it("reports the libduckdb loaded into this process") {
      val linked = NativeBuildInfo.fields(QuackNativeBridge.linkedDuckDb())
      linked
        .get("duckdb") shouldBe NativeBuildInfo.fields(QuackNativeBridge.buildInfo()).get("duckdb")
      linked.get("duckdb_source").getOrElse("") should fullyMatch regex "[0-9a-f]+"
    }
