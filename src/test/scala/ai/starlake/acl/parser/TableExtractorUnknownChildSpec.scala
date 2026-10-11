package ai.starlake.acl.parser

import net.sf.jsqlparser.expression.{Expression, MapExpression, StringValue}
import net.sf.jsqlparser.statement.select.PlainSelect
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The reflective walk reaches children by runtime type. A child of a type it has no rule for may
  * hold a subquery it cannot see, so it must surface as unsupported (a deny) rather than be
  * skipped.
  */
class TableExtractorUnknownChildSpec extends AnyFlatSpec with Matchers:

  "TableExtractor" should "report a child of an unknown type as unsupported" in:
    val entries = new java.util.ArrayList[java.util.Map.Entry[AnyRef, AnyRef]]()
    entries.add(
      new java.util.AbstractMap.SimpleEntry[AnyRef, AnyRef](new StringValue("k"), new Object)
    )
    val map = new MapExpression()
    map.setEntries(
      entries.asInstanceOf[java.util.List[java.util.Map.Entry[Expression, Expression]]]
    )
    val select = new PlainSelect()
    select.addSelectItem(map)
    TableExtractor.extract(select).unsupported should not be empty

  it should "treat names, numbers and flags as leaves" in:
    AstChildren.isLeaf("x") shouldBe true
    AstChildren.isLeaf(java.lang.Long.valueOf(1)) shouldBe true
    AstChildren.isLeaf(java.lang.Boolean.TRUE) shouldBe true
    AstChildren.isLeaf(new Object) shouldBe false
