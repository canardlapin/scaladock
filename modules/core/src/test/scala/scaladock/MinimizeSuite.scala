package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import TestPanes.*

/** True minimize: a group collapses to a header-thin strip in place. The design's central promise —
  * minimise is presentational, sizes are never touched — is what these laws pin.
  */
final class MinimizeSuite extends ScalaCheckSuite:

  private val settings = LayoutSettings.default

  private val stateWithGroup: Gen[(LayoutState, Node.Group)] =
    stateGen.map(_.copy(minimized = Set.empty)).flatMap: s =>
      Gen.oneOf(s.groups).map(g => (s, g))

  property("minimize then unminimize is the exact identity — sizes are never touched"):
    forAll(stateWithGroup) { case (s, g) =>
      val base = edit.unmaximized(s)
      assertEquals(edit.unminimize(edit.minimize(base, g.id), g.id), base)
      true
    }

  property("a minimized group presents as a strip; siblings absorb the space; restore is exact"):
    forAll(stateWithGroup) { case (s, g) =>
      val viewport = Rect(0, 0, 1600, 1000)
      val before   = sizing.geometry(s.root, viewport, settings, s.maximized)
      val minned   = edit.minimize(edit.unmaximized(s), g.id)
      val during =
        sizing.geometry(minned.root, viewport, settings, minned.maximized, minned.minimized)

      // in the main window, the strip constrains the group along its parent split's axis
      if s.root.exists(_.findGroup(g.id).isDefined) then
        val rect = during.groups(g.id).bounds
        // g lives in the main root, so any parent split does too
        edit.parentOf(s, g.id) match
          case Some((sp, _)) =>
            val extent = sp.axis match
              case Axis.Horizontal => rect.width
              case Axis.Vertical   => rect.height
            assertEqualsDouble(extent, sizing.stripPx(settings), 0.6)
          case None => // root group: a top strip
            assertEqualsDouble(rect.height, sizing.stripPx(settings), 0.6)
        assertEquals(during.groups(g.id).content.area, 0.0, "no content rect while minimized")

      // restoring reproduces the original geometry exactly: nothing was forgotten
      val restored = edit.unminimize(minned, g.id)
      val after =
        sizing.geometry(restored.root, viewport, settings, restored.maximized, restored.minimized)
      assertEquals(after, before)
      true
    }

  property("focusing a pane restores its minimized group"):
    forAll(stateWithGroup) { case (s, g) =>
      val minned = edit.minimize(s, g.id)
      val next   = edit.focus(minned, g.active)
      assert(!next.minimized(g.id))
      assertEquals(next.focused, Some(g.active))
      true
    }

  property("dropping into a minimized group restores it"):
    forAll(stateWithGroup, paneGen) { case ((s, g), pane) =>
      val minned = edit.minimize(s, g.id)
      val next   = edit.drop(minned, pane, DropTarget.IntoGroup(g.id, 0))
      assert(!next.minimized(g.id))
      assert(next.findGroup(g.id).exists(_.tabs.exists(_.id == pane.id)))
      true
    }

  property("maximize and minimize are mutually exclusive per group"):
    forAll(stateWithGroup) { case (s, g) =>
      val maxThenMin = edit.minimize(edit.maximize(s, g.id), g.id)
      assertEquals(maxThenMin.maximized, None)
      assert(maxThenMin.minimized(g.id))
      val minThenMax = edit.maximize(edit.minimize(s, g.id), g.id)
      assertEquals(minThenMax.maximized, Some(g.id))
      assert(!minThenMax.minimized(g.id))
      true
    }

  property("canonical clears minimized ids whose group is gone"):
    forAll(stateWithGroup) { case (s, g) =>
      val minned  = edit.minimize(s, g.id)
      val emptied = g.tabs.foldLeft(minned)((acc, p) => edit.close(acc, p.id))
      assert(!emptied.minimized(g.id))
      Invariants.assertCanonical(emptied)
      true
    }

  property("the minimized set survives the codec round trip"):
    forAll(stateWithGroup) { case (s, g) =>
      val minned = edit.minimize(s, g.id)
      LayoutCodec.decode(LayoutCodec.encode(minned), registry) match
        case Right(decoded) => assertEquals(decoded, minned)
        case Left(err)      => fail(s"decode failed: $err")
      true
    }

  test("diff reports minimize and restore"):
    val a  = doc("a")
    val s0 = edit.canonical(LayoutState.of(Node.solo(a)))
    val g  = s0.groups.head.id
    val s1 = edit.minimize(s0, g)
    assertEquals(DockEvent.diff(s0, s1), Vector(DockEvent.GroupMinimized(g)))
    val s2 = edit.unminimize(s1, g)
    assertEquals(DockEvent.diff(s1, s2), Vector(DockEvent.GroupUnminimized(g)))
end MinimizeSuite
