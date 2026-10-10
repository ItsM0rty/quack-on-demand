package ai.starlake.quack

import com.typesafe.config.ConfigFactory
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** `QOD_NATIVE_CLIENT` and `QOD_NODE_DISABLE_SSL` went with the embedded fallback client. An
  * operator environment that still exports them, even with values the old Boolean keys would have
  * refused, must load the manager config unchanged.
  */
class RemovedClientConfigSpec extends AnyFunSpec with Matchers:
  import Main.given

  /** Loads `quack-on-demand` with `env` resolved as HOCON resolves environment variables: a
    * root-level key wins over the process environment for every `${?VAR}` substitution, so this
    * simulates the variables being exported without mutating the JVM's environment.
    */
  private def loadWith(env: Map[String, String]): ManagerConfig =
    val overlay = ConfigFactory.parseString(
      env.map((k, v) => s"$k = \"$v\"").mkString("\n")
    )
    val resolved = ConfigFactory.load(
      overlay.withFallback(ConfigFactory.parseResourcesAnySyntax("application"))
    )
    ConfigSource.fromConfig(resolved).at("quack-on-demand").loadOrThrow[ManagerConfig]

  it("ignores QOD_NATIVE_CLIENT and QOD_NODE_DISABLE_SSL, whatever their value"):
    val baseline = loadWith(Map.empty)
    val withOld  = loadWith(
      Map("QOD_NATIVE_CLIENT" -> "not-a-boolean", "QOD_NODE_DISABLE_SSL" -> "not-a-boolean")
    )
    withOld shouldBe baseline

  it("has no client-mode or node-TLS key on the manager config"):
    (loadWith(Map.empty).productElementNames.toList should contain)
      .noneOf("nativeClient", "nodeDisableSsl")
