package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

final class GeometrySuite extends ScalaCheckSuite:

  test("Edge axis and leading are consistent"):
    assertEquals(Edge.Left.axis, Axis.Horizontal)
    assertEquals(Edge.Top.axis, Axis.Vertical)
    assert(Edge.Left.leading)
    assert(!Edge.Bottom.leading)

  test("Axis.cross is an involution"):
    Axis.values.foreach(a => assertEquals(a.cross.cross, a))

  property("Rect.contains respects exclusive right/bottom bounds"):
    forAll { (x: Short, y: Short, w: Short, h: Short) =>
      val r = Rect(x.toDouble, y.toDouble, w.toDouble.abs.max(1), h.toDouble.abs.max(1))
      r.contains(Point(r.x, r.y)) && !r.contains(Point(r.right, r.bottom))
    }
