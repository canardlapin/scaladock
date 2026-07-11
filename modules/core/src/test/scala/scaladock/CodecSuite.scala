package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import TestPanes.*

final class CodecSuite extends ScalaCheckSuite:

  property("encode/decode round-trips canonical states exactly"):
    forAll(stateGen) { s =>
      LayoutCodec.decode(LayoutCodec.encode(s), registry) match
        case Right(decoded) => assertEquals(decoded, s)
        case Left(err)      => fail(s"decode failed: $err")
      true
    }

  property("unknown pane types survive a save/load/save cycle byte-for-byte"):
    forAll(stateGen) { s =>
      val saved = LayoutCodec.encode(s)
      // load in an app that knows none of the pane types...
      val loaded = LayoutCodec.decode(saved, PaneTypes.empty)
        .getOrElse(fail("decode with empty registry failed"))
      // ...every pane is carried as Unresolved...
      loaded.panes.foreach(p => assert(p.content.tpe eq PaneType.Unresolved))
      // ...and saving again loses nothing
      assertEquals(LayoutCodec.encode(loaded), saved)
      true
    }

  property("unresolved panes resolve on the next load once the type is known"):
    forAll(stateGen) { s =>
      val throughStranger = LayoutCodec
        .decode(LayoutCodec.encode(s), PaneTypes.empty)
        .map(LayoutCodec.encode)
        .getOrElse(fail("stranger round-trip failed"))
      assertEquals(LayoutCodec.decode(throughStranger, registry), Right(s))
      true
    }

  test("malformed input yields LoadError, not an exception"):
    assert(LayoutCodec.decode(ujson.Str("nonsense"), registry).isLeft)
    assert(LayoutCodec.decode(ujson.Obj("version" -> 1), registry).isLeft)

  test("the persisted format is pinned (golden fixture)"):
    val a = Pane(PaneId("p-doc"), PaneContent(Text, Doc("readme", 42)), "README")
    val b = Pane(PaneId("p-num"), PaneContent(Counter, 7), "seven", closable = false)
    val state = LayoutState(
      root = Some(
        Node.Split(
          NodeId("s-root"),
          Axis.Horizontal,
          Vector(
            Cell(Node.Group(NodeId("g-left"), Vector(a), a.id), Size.Px(260), minPx = 120),
            Cell(Node.Group(NodeId("g-main"), Vector(b), b.id, Header.Hidden), Size.Fr(1))
          )
        )
      ),
      floating = Vector(
        Floating(
          WindowId("w-1"),
          Rect(20, 30, 400, 300),
          Node.Group(NodeId("g-float"), Vector(counterAt("p-f", 1)), PaneId("p-f")),
          Some(Anchor(NodeId("g-left"), Edge.Right, Size.Pct(50), Size.Fr(1), minPx = 80))
        )
      ),
      maximized = None,
      focused = Some(a.id)
    )

    val expected =
      """{"version":1,"root":{"split":{"id":"s-root","axis":"row","cells":[""" +
        """{"size":"260px","minPx":120,"node":{"group":{"id":"g-left","active":"p-doc","header":"inherit",""" +
        """"tabs":[{"id":"p-doc","title":"README","closable":true,"type":"test.text","state":{"name":"readme","cursor":42}}]}}},""" +
        """{"size":"1fr","node":{"group":{"id":"g-main","active":"p-num","header":"hidden",""" +
        """"tabs":[{"id":"p-num","title":"seven","closable":false,"type":"test.counter","state":7}]}}}]}},""" +
        """"floating":[{"window":"w-1","bounds":{"x":20,"y":30,"width":400,"height":300},""" +
        """"home":{"sibling":"g-left","edge":"right","size":"50%","siblingSize":"1fr","minPx":80},""" +
        """"root":{"group":{"id":"g-float","active":"p-f","header":"inherit",""" +
        """"tabs":[{"id":"p-f","title":"one","closable":true,"type":"test.counter","state":1}]}}}],""" +
        """"maximized":null,"minimized":[],"focused":"p-doc"}"""

    assertEquals(ujson.write(LayoutCodec.encode(state)), expected)

  private def counterAt(id: String, n: Int): Pane =
    Pane(PaneId(id), PaneContent(Counter, n), "one")
end CodecSuite
