package scaladock

import org.scalacheck.Gen
import upickle.default.ReadWriter

/** Shared pane types and tree generators for the property suites. */
object TestPanes:

  final case class Doc(name: String, cursor: Int) derives ReadWriter

  val Text: PaneType[Doc]    = PaneType[Doc]("test.text")
  val Counter: PaneType[Int] = PaneType[Int]("test.counter")

  val registry: PaneTypes = PaneTypes(Text, Counter)

  def doc(name: String): Pane = Text(Doc(name, 0)).titled(name).toPane
  def counter(n: Int): Pane   = Counter(n).titled(s"n$n").toPane

  // -- generators --------------------------------------------------------------------------

  val paneGen: Gen[Pane] = Gen.oneOf(
    Gen.identifier.map(s => doc(s.take(8))),
    Gen.choose(0, 999).map(counter)
  )

  val sizeGen: Gen[Size] = Gen.oneOf(
    Gen.choose(0.5, 4.0).map(Size.Fr.apply),
    Gen.choose(5.0, 90.0).map(Size.Pct.apply),
    Gen.choose(40.0, 400.0).map(Size.Px.apply)
  )

  /** A possibly non-canonical group: the active tab may dangle, tabs may be empty. */
  val rawGroupGen: Gen[Node] =
    for
      count <- Gen.frequency(5 -> Gen.choose(1, 3), 1 -> Gen.const(0))
      tabs  <- Gen.listOfN(count, paneGen).map(_.toVector)
      active <-
        if tabs.isEmpty then Gen.const(PaneId.fresh())
        else // mostly valid, sometimes dangling on purpose: canonical must repair it
          Gen.frequency(4 -> Gen.oneOf(tabs.map(_.id)), 1 -> Gen.const(PaneId.fresh()))
    yield Node.Group(NodeId.fresh(), tabs, active)

  /** A possibly non-canonical tree: single-cell splits and same-axis nesting included. */
  def rawNodeGen(depth: Int): Gen[Node] =
    if depth <= 0 then rawGroupGen
    else
      Gen.frequency(
        2 -> rawGroupGen,
        3 -> (
          for
            axis  <- Gen.oneOf(Axis.Horizontal, Axis.Vertical)
            n     <- Gen.choose(1, 3)
            cells <- Gen.listOfN(n, cellGen(depth - 1)).map(_.toVector)
          yield Node.Split(NodeId.fresh(), axis, cells)
        )
      )

  private def cellGen(depth: Int): Gen[Cell] =
    for
      node  <- rawNodeGen(depth)
      size  <- sizeGen
      minPx <- Gen.oneOf(Gen.const(0.0), Gen.choose(10.0, 120.0))
    yield Cell(node, size, minPx)

  val rawStateGen: Gen[LayoutState] =
    for
      root <- Gen.frequency(5 -> rawNodeGen(3).map(Some(_)), 1 -> Gen.const(None))
      floating <- Gen.frequency(3 -> Gen.const(0), 2 -> Gen.choose(1, 2))
        .flatMap(n => Gen.listOfN(n, rawNodeGen(2)).map(_.toVector))
    yield LayoutState(
      root,
      floating.map(r => Floating(WindowId.fresh(), Rect(0, 0, 640, 480), r, None))
    )

  /** Canonical, non-trivial states: what every transition receives in practice. */
  val stateGen: Gen[LayoutState] =
    rawStateGen.map(edit.canonical).filter(_.panes.nonEmpty)

  val cellsGen: Gen[Vector[Cell]] =
    Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, cellGen(0)).map(_.toVector))
end TestPanes
