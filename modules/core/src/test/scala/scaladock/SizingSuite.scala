package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import TestPanes.*

final class SizingSuite extends ScalaCheckSuite:

  private val eps = 1e-6

  private val spanGen = Gen.choose(100.0, 3000.0)

  property("allocation sums exactly to the available span"):
    forAll(cellsGen, spanGen) { (cells, span) =>
      val available = math.max(0.0, span - (cells.length - 1) * 5.0)
      val alloc     = sizing.allocate(cells, span, 5.0)
      assertEqualsDouble(alloc.sum, available, eps)
      true
    }

  property("min sizes are honoured whenever they are jointly feasible"):
    forAll(cellsGen, spanGen) { (cells, span) =>
      val available = math.max(0.0, span - (cells.length - 1) * 5.0)
      val alloc     = sizing.allocate(cells, span, 5.0)
      (cells.map(_.minPx).sum <= available) ==> Prop(
        cells.indices.forall(i => alloc(i) >= cells(i).minPx - eps)
      )
    }

  test("fr cells share the remainder by weight"):
    val cells = Vector(
      Cell(Node.solo(doc("a")), Size.Fr(1)),
      Cell(Node.solo(doc("b")), Size.Fr(3))
    )
    val alloc = sizing.allocate(cells, 405, 5.0) // available = 400
    assertEqualsDouble(alloc(0), 100.0, eps)
    assertEqualsDouble(alloc(1), 300.0, eps)

  test("px cells take their pixels when fr capacity absorbs the rest"):
    val cells = Vector(
      Cell(Node.solo(doc("side")), Size.Px(260)),
      Cell(Node.solo(doc("main")), Size.Fr(1))
    )
    val alloc = sizing.allocate(cells, 1005, 5.0) // available = 1000
    assertEqualsDouble(alloc(0), 260.0, eps)
    assertEqualsDouble(alloc(1), 740.0, eps)

  test("pct cells take their share of the available span"):
    val cells = Vector(
      Cell(Node.solo(doc("a")), Size.Pct(25)),
      Cell(Node.solo(doc("b")), Size.Fr(1))
    )
    val alloc = sizing.allocate(cells, 405, 5.0)
    assertEqualsDouble(alloc(0), 100.0, eps)
    assertEqualsDouble(alloc(1), 300.0, eps)

  property("commitDivider changes only the two adjacent cells"):
    forAll(cellsGen.filter(_.length >= 2), spanGen, Gen.choose(0.0, 1.0)) { (cells, span, f) =>
      val index = 0
      val next =
        sizing.commitDivider(cells, Axis.Horizontal, index, f, span, LayoutSettings.default)
      assertEquals(next.length, cells.length)
      cells.indices.drop(2).foreach(i => assertEquals(next(i), cells(i)))
      assertEquals(next(index).node, cells(index).node)
      assertEquals(next(index + 1).node, cells(index + 1).node)
      true
    }

  property("commitDivider preserves the neighbours' combined pixels in the normalized regime"):
    // The exact-pair guarantee holds when the split is neither over- nor under-subscribed:
    // fixed (px/pct) claims fit the span and some fr capacity absorbs the slack. Outside that
    // regime allocation rescales proportionally and a commit is best-effort (golden-layout
    // never leaves the regime at all — it has no px sizes). Min pressure is likewise excluded
    // here; it is exercised by its own tests.
    val dividerPx    = LayoutSettings.default.dividerPx
    val minFreeCells = cellsGen.filter(_.length >= 2).map(_.map(_.copy(minPx = 0)))
    forAll(minFreeCells, spanGen, Gen.choose(0.0, 1.0)) { (cells, span, f) =>
      val available = math.max(0.0, span - (cells.length - 1) * dividerPx)
      val claims = cells.map: c =>
        c.size match
          case Size.Px(v)  => math.max(0.0, v)
          case Size.Pct(p) => math.max(0.0, p) / 100.0 * available
          case Size.Fr(_)  => 0.0
      val hasFr = cells.exists(c =>
        c.size match
          case Size.Fr(_) => true;
          case _          => false
      )
      (hasFr && claims.sum <= available) ==> {
        val before = sizing.allocate(cells, span, dividerPx)
        val after = sizing.allocate(
          sizing.commitDivider(cells, Axis.Horizontal, 0, f, span, LayoutSettings.default),
          span,
          dividerPx
        )
        assertEqualsDouble(after(0) + after(1), before(0) + before(1), 1e-3)
        true
      }
    }

  test("commitDivider lands the divider at the requested fraction"):
    val cells = Vector(
      Cell(Node.solo(doc("a")), Size.Fr(1)),
      Cell(Node.solo(doc("b")), Size.Fr(1))
    )
    val next = sizing.commitDivider(
      cells,
      Axis.Horizontal,
      0,
      0.25,
      805,
      LayoutSettings.default
    ) // available = 800
    val alloc = sizing.allocate(next, 805, 5.0)
    assertEqualsDouble(alloc(0), 200.0, eps)
    assertEqualsDouble(alloc(1), 600.0, eps)

  test("commitDivider clamps to the neighbours' min sizes"):
    val cells = Vector(
      Cell(Node.solo(doc("a")), Size.Fr(1), minPx = 150),
      Cell(Node.solo(doc("b")), Size.Fr(1), minPx = 100)
    )
    val next  = sizing.commitDivider(cells, Axis.Horizontal, 0, 0.0, 805, LayoutSettings.default)
    val alloc = sizing.allocate(next, 805, 5.0)
    assert(alloc(0) >= 150.0 - eps, s"left neighbour squeezed below min: ${alloc(0)}")

  property("geometry tiles the viewport: no group escapes it"):
    forAll(stateGen, spanGen, spanGen) { (s, w, h) =>
      val viewport = Rect(0, 0, w, h)
      val geom     = sizing.geometry(s.root, viewport, LayoutSettings.default)
      geom.groups.values.foreach: gg =>
        assert(gg.bounds.x >= -eps && gg.bounds.y >= -eps)
        assert(gg.bounds.right <= viewport.right + eps)
        assert(gg.bounds.bottom <= viewport.bottom + eps)
      true
    }

  test("default metrics: a hairline 1px divider and a 32px header"):
    assertEquals(LayoutSettings.default.dividerPx, 1.0)
    assertEquals(LayoutSettings.default.headerPx, 32.0)
    val pair = Vector(Cell(Node.solo(doc("a"))), Cell(Node.solo(doc("b"))))
    val s    = edit.canonical(LayoutState.of(Node.Split(NodeId.fresh(), Axis.Horizontal, pair)))
    val geom = sizing.geometry(s.root, Rect(0, 0, 801, 600), LayoutSettings.default)
    assertEquals(geom.dividers.map(_.bounds.width), Vector(1.0))
    geom.groups.values.foreach: gg =>
      assertEqualsDouble(gg.bounds.width, 400.0, eps)
      assertEqualsDouble(gg.header.height, 32.0, eps)
      assertEqualsDouble(gg.content.height, 600.0 - 32.0, eps)

  property("hitTest picks the smallest containing hover area"):
    forAll(stateGen, Gen.choose(0.0, 800.0), Gen.choose(0.0, 600.0)) { (s, px, py) =>
      val viewport = Rect(0, 0, 800, 600)
      val geom     = sizing.geometry(s.root, viewport, LayoutSettings.default)
      val areas    = sizing.dropAreas(s.root, geom, viewport, None, LayoutSettings.default)
      val p        = Point(px, py)
      sizing.hitTest(areas, p) match
        case Some(hit) =>
          assert(hit.hover.contains(p))
          areas.filter(_.hover.contains(p)).foreach(a => assert(hit.hover.area <= a.hover.area))
        case None => ()
      true
    }
end SizingSuite
