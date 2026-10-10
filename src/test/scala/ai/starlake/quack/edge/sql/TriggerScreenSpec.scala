package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TriggerScreenSpec extends AnyFlatSpec with Matchers:

  private def refused(sql: String): Boolean = TriggerScreen.screen(sql).isDefined

  private def eff(tenant: Option[String], kind: String): Option[EffectiveSet] =
    Some(EffectiveSet(RbacUser("u", tenant, "u", kind), Nil, Nil, Nil, Nil))

  "screen" should "flag every trigger DDL spelling" in {
    refused(
      "CREATE TRIGGER t AFTER INSERT ON a FOR EACH STATEMENT INSERT INTO b VALUES (1)"
    ) shouldBe true
    refused(
      "create or replace trigger t after insert on a for each statement select 1"
    ) shouldBe true
    refused("CREATE TEMP TRIGGER t AFTER INSERT ON a FOR EACH STATEMENT SELECT 1") shouldBe true
    refused(
      "CREATE TEMPORARY TRIGGER t AFTER INSERT ON a FOR EACH STATEMENT SELECT 1"
    ) shouldBe true
    refused(
      "CREATE TRIGGER IF NOT EXISTS t AFTER INSERT ON a FOR EACH STATEMENT SELECT 1"
    ) shouldBe true
    refused("DROP TRIGGER t") shouldBe true
    refused("/* x */ create\ntrigger t after insert on a for each statement select 1") shouldBe true
    refused("SELECT 1; DROP TRIGGER t") shouldBe true
    refused(
      "EXPLAIN ANALYZE CREATE TRIGGER t AFTER INSERT ON a FOR EACH STATEMENT SELECT 1"
    ) shouldBe true
  }

  it should "leave everything else alone" in {
    refused("CREATE TABLE triggers(a INT)") shouldBe false
    refused("SELECT 'CREATE TRIGGER t' AS s") shouldBe false
    refused("INSERT INTO trigger_log VALUES (1)") shouldBe false
  }

  it should "carry the documented refusal message" in {
    TriggerScreen.screen("DROP TRIGGER t") shouldBe Some(TriggerScreen.RefusalMessage)
    TriggerScreen.RefusalMessage shouldBe "CREATE TRIGGER is restricted to tenant administrators"
  }

  "mayManageTriggers" should "admit superusers and admins of the pool's own tenant only" in {
    TriggerScreen.mayManageTriggers(eff(None, "admin"), Some("acme")) shouldBe true
    TriggerScreen.mayManageTriggers(eff(Some("acme"), "admin"), Some("acme")) shouldBe true
    TriggerScreen.mayManageTriggers(eff(Some("acme"), "user"), Some("acme")) shouldBe false
    TriggerScreen.mayManageTriggers(eff(Some("globex"), "admin"), Some("acme")) shouldBe false
    TriggerScreen.mayManageTriggers(eff(Some("acme"), "admin"), None) shouldBe false
    TriggerScreen.mayManageTriggers(None, Some("acme")) shouldBe false
  }
