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
  test("layout metrics reject negative and non-finite fields but allow zero"):
    for bad <- Vector(-0.01, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      val _ = intercept[IllegalArgumentException](LayoutSettings(dividerPx = bad))
      val _ = intercept[IllegalArgumentException](LayoutSettings(headerPx = bad))
      val _ = intercept[IllegalArgumentException](LayoutSettings(edgeBandPx = bad))
      val _ = intercept[IllegalArgumentException](LayoutSettings(defaultMinPanePx = bad))
    assertEquals(LayoutSettings(0, 0, 0, 0).headerPx, 0.0)

  test("zero and tiny viewports keep chrome, content and hit areas finite and inside bounds"):
    val groups = Vector("a", "b", "c").map(t => Node.solo(doc(t)))
    val root   = Node.Split(NodeId.fresh(), Axis.Horizontal, groups.map(g => Cell(g)))
    for
      metrics <- Vector(
        LayoutSettings(),
        LayoutSettings(0, 0, 0, 0),
        LayoutSettings(headerPx = 80),
        LayoutSettings(defaultMinPanePx = Double.MaxValue)
      )
      span <- Vector(0.0, 0.25, 1.0, 20.0)
    do
      val viewport = Rect(0, 0, span, span)
      val geom =
        sizing.geometry(Some(root), viewport, metrics, minimized = groups.take(2).map(_.id).toSet)
      val areas = sizing.dropAreas(Some(root), geom, viewport, None, metrics)
      val rects = geom.groups.values.toVector.flatMap(g => Vector(g.bounds, g.header, g.content)) ++
        geom.dividers.map(_.bounds) ++ areas.flatMap(a => Vector(a.hover, a.highlight))
      rects.foreach: r =>
        assert(Vector(r.x, r.y, r.width, r.height).forall(_.isFinite), s"$metrics $span $r")
        assert(r.width >= 0 && r.height >= 0)
        assert(r.x >= 0 && r.y >= 0 && r.right <= span + eps && r.bottom <= span + eps)

  test("a maximized hidden header remains absent from geometry and hit tests"):
    val group = Node.solo(doc("hidden")).copy(header = Header.Hidden)
    val geom = sizing.geometry(
      Some(group),
      Rect(0, 0, 200, 100),
      LayoutSettings(headerPx = 64),
      Some(group.id)
    )
    assertEquals(geom.groups(group.id).header.height, 0.0)
    assertEquals(geom.groups(group.id).content, Rect(0, 0, 200, 100))

  test("divider commit uses actual constrained live span beside a minimized sibling"):
    val groups  = Vector("a", "b", "strip").map(t => Node.solo(doc(t)))
    val cells   = Vector(Cell(groups(0)), Cell(groups(1)), Cell(groups(2), Size.Px(250)))
    val root    = Node.Split(NodeId.fresh(), Axis.Vertical, cells)
    val state   = edit.canonical(LayoutState.of(root)).copy(minimized = Set(groups(2).id))
    val metrics = LayoutSettings(headerPx = 64)
    val next    = edit.dragDivider(state, root.id, 0, 0.4, 600, metrics)
    val geom = sizing.geometry(next.root, Rect(0, 0, 400, 600), metrics, minimized = next.minimized)
    assertEqualsDouble(geom.groups(groups(0).id).bounds.height, 213.6, eps)
    assertEqualsDouble(geom.groups(groups(1).id).bounds.height, 320.4, eps)
    assertEqualsDouble(geom.groups(groups(2).id).bounds.height, 64.0, eps)
    assertEquals(next.findSplit(root.id).get.cells(2), cells(2))
    assertEquals(next.groups.map(_.id), state.groups.map(_.id))

end SizingSuite
