package scaladock.fx

import javafx.application.Platform
import javafx.event.Event
import javafx.scene.{Cursor, Scene}
import javafx.scene.control.Label
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent}
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Uses live native Stages and real pulses; no manually invoked AnimationTimer handles. */
final class MotionSuite extends FunSuite:
  final case class Text(value: String) derives ReadWriter
  private val TextPane = PaneType[Text]("test.motion.text")
  private val first    = Rect(10, 30, 140, 90)
  private val target   = Rect(260, 180, 180, 150)

  private def fixture(): (Dock, Stage, StackPane) =
    val factories = PaneFactories.empty.register(TextPane): text =>
      new PaneView[Text]:
        val node             = new Label(text.value)
        def snapshot(): Text = text
    val d = Dock(
      factories,
      initial = LayoutState.of(row(
        group(TextPane(Text("a")).titled("a"), TextPane(Text("b")).titled("b")),
        group(TextPane(Text("c")).titled("c"))
      ))
    )
    val root  = new StackPane(d.view)
    val stage = new Stage
    stage.setTitle("ScalaDock S5 synthetic motion probe")
    stage.setScene(new Scene(root, 700, 450))
    stage.show()
    root.applyCss(); root.layout()
    (d, stage, root)

  private def start(d: Dock): (DockRegion, GroupView) =
    val r = d.view.asInstanceOf[DockRegion]
    val g = d.groupViewFor(d.state.groups.head.id).get
    r.showIndicator(first)
    r.showIndicator(target)
    g.previewInsertion(Some((0, 60)))
    (r, g)

  private def tabs(g: GroupView): Vector[javafx.scene.Node] =
    g.lookupAll(".dock-tab").toArray.toVector.map(_.asInstanceOf[javafx.scene.Node])

  private def trace(label: String, d: Dock, r: DockRegion, g: GroupView): Unit =
    println("MOTION_TRACE " + ujson.Obj(
      "label"              -> label,
      "java"               -> System.getProperty("java.version"),
      "javafx"             -> System.getProperty("javafx.runtime.version"),
      "policy"             -> d.motion.toString,
      "reduced"            -> d.isReducedMotion,
      "indicatorAnimating" -> r.indicatorAnimating,
      "shownX"             -> r.indicatorShown.fold(-1.0)(_.x),
      "targetX"            -> r.indicatorTarget.fold(-1.0)(_.x),
      "slides"             -> g.slideCount
    ).render())

  test("system follows the floor OS preference; explicit overrides and validation preserve state"):
    onFx:
      val (d, stage, _) = fixture()
      try
        assertEquals(d.motion, DockMotion.System)
        assertEquals(d.isReducedMotion, Platform.getPreferences.isReducedMotion)
        val state = d.state
        val nodes = state.panes.map(p => d.nodeOf(p.id).get)
        d.setMotion(DockMotion.Reduced)
        assert(d.isReducedMotion)
        d.setMotion(DockMotion.Full)
        assert(!d.isReducedMotion)
        d.setMotion(DockMotion.System)
        assertEquals(d.isReducedMotion, Platform.getPreferences.isReducedMotion)
        val _ = intercept[IllegalArgumentException](d.setMotion(null))
        assertEquals(d.state, state)
        assertEquals(state.panes.map(p => d.nodeOf(p.id).get), nodes)
      finally
        d.dispose(); stage.close()
      intercept[IllegalArgumentException](d.setMotion(DockMotion.Full))

  test("reduced motion draws exact targets immediately without fades or slides"):
    onFx:
      val (d, stage, _) = fixture()
      try
        d.setMotion(DockMotion.Reduced)
        val (r, g) = start(d)
        assertEquals(r.indicatorShown, Some(DockRegion.inset(target, 4)))
        assert(!r.indicatorAnimating)
        assertEquals(r.lookup(".dock-drop-indicator").getOpacity, 1.0)
        assertEquals(g.slideCount, 0)
        assert(tabs(g).forall(_.getTranslateX == 60))
        r.showIndicator(first, slot = true)
        assertEquals(r.indicatorShown, Some(first))
        g.previewInsertion(None)
        assert(tabs(g).forall(_.getTranslateX == 0))
        trace("reduced-immediate", d, r, g)
      finally
        d.dispose(); stage.close()

  test(
    "switching to reduced mid-animation settles the existing target without cancelling feedback"
  ):
    onFx:
      val (d, stage, _) = fixture()
      try
        d.setMotion(DockMotion.Full)
        val (r, g) = start(d)
        assert(r.indicatorAnimating && g.slideCount > 0)
        assert(r.indicatorShown != r.indicatorTarget)
        trace("full-start", d, r, g)
        d.setMotion(DockMotion.Reduced)
        assertEquals(r.indicatorShown, r.indicatorTarget)
        assert(r.lookup(".dock-drop-indicator").isVisible)
        assertEquals(r.lookup(".dock-drop-indicator").getOpacity, 1.0)
        assert(!r.indicatorAnimating && g.slideCount == 0)
        assert(tabs(g).forall(_.getTranslateX == 60))
        trace("switched-reduced", d, r, g)
        d.setMotion(DockMotion.Full)
        assert(!r.indicatorAnimating && g.slideCount == 0)
      finally
        d.dispose(); stage.close()

  test("full motion advances on native pulses and stops when settled"):
    val (d, stage, r, g) = onFx:
      val (d, stage, _) = fixture()
      d.setMotion(DockMotion.Full)
      val (r, g) = start(d)
      (d, stage, r, g)
    try
      var moving   = false
      var settled  = false
      val deadline = System.nanoTime() + 3000000000L
      while !settled && System.nanoTime() < deadline do
        Thread.sleep(25)
        settled = onFx:
          if r.indicatorShown != r.indicatorTarget &&
            r.indicatorShown != Some(DockRegion.inset(first, 4))
          then moving = true
          trace("full-pulse", d, r, g)
          !r.indicatorAnimating && g.slideCount == 0
      onFx:
        assert(moving, "native pulses must move the custom timer through intermediate positions")
        assert(settled, "custom animations must finish within a bounded interval")
        assertEquals(r.indicatorShown, r.indicatorTarget)
        assert(tabs(g).forall(_.getTranslateX == 60))
    finally onFx { d.dispose(); stage.close() }

  test("hidden ancestors, detached roots, hidden windows and disposal stop all custom motion"):
    for change <- Vector("ancestor", "detached", "window", "dispose") do
      onFx:
        val (d, stage, root) = fixture()
        try
          d.setMotion(DockMotion.Full)
          val (r, g) = start(d)
          assert(r.indicatorAnimating && g.slideCount > 0)
          change match
            case "ancestor" => root.setVisible(false)
            case "detached" => root.getChildren.clear()
            case "window"   => stage.hide()
            case _          => d.dispose()
          assert(!r.indicatorAnimating && g.slideCount == 0, change)
          assert(r.indicatorTarget.isEmpty, change)
          assert(tabs(g).forall(_.getTranslateX == 0), change)
          r.showIndicator(target)
          assert(!r.indicatorAnimating && r.indicatorTarget.isEmpty)
          trace(change, d, r, g)
        finally
          d.dispose(); stage.close()

  test("policy changes survive theme and density updates and floating-owner closure"):
    onFx:
      val (d, stage, _) = fixture()
      try
        d.setMotion(DockMotion.Full)
        val (r, g) = start(d)
        d.setTheme(DockTheme.Light)
        assert(!r.indicatorAnimating && g.slideCount == 0)
        val _ = start(d)
        d.setSettings(LayoutSettings(headerPx = 36))
        assert(!r.indicatorAnimating && g.slideCount == 0)
        d.popOut(d.state.groups.head.id)
        val floating = d.dragSurfaces.find(_._1.isDefined).get._2
        val fg       = d.groupViewFor(d.state.floating.head.root.groups.head.id).get
        floating.showIndicator(first); floating.showIndicator(target)
        fg.previewInsertion(Some((0, 40)))
        assert(floating.indicatorAnimating && fg.slideCount > 0)
        d.setMotion(DockMotion.Reduced)
        assert(!floating.indicatorAnimating && fg.slideCount == 0)
        assertEquals(floating.indicatorShown, floating.indicatorTarget)
        d.setMotion(DockMotion.Full)
        floating.showIndicator(first)
        fg.previewInsertion(None)
        assert(floating.indicatorAnimating)
        floating.getScene.getWindow.hide()
        assert(!floating.indicatorAnimating && fg.slideCount == 0)
        d.setMotion(DockMotion.Reduced)
        trace("floating-hidden", d, floating, fg)
      finally
        d.dispose(); stage.close()

  private def mouse(kind: javafx.event.EventType[MouseEvent], sx: Double, sy: Double): MouseEvent =
    new MouseEvent(
      kind,
      0,
      0,
      sx,
      sy,
      MouseButton.PRIMARY,
      1,
      false,
      false,
      false,
      false,
      true,
      false,
      false,
      true,
      false,
      false,
      null
    )

  test("switching motion during a real tab drag preserves the drop, pointer movement and pane"):
    onFx:
      val (d, stage, _) = fixture()
      try
        d.setMotion(DockMotion.Full)
        val left     = d.state.groups.head
        val right    = d.state.groups.last
        val pane     = left.tabs.last.id
        val original = d.nodeOf(pane).get
        val tab      = tabs(d.groupViewFor(left.id).get).find(_.getAccessibleText == "b").get
        val from     = tab.localToScreen(5, 5)
        val r        = d.view.asInstanceOf[DockRegion]
        val body     = r.geometry.groups(right.id).content
        val to       = r.localToScreen(body.x + body.width / 2, body.y + body.height * 0.75)
        Event.fireEvent(tab, mouse(MouseEvent.MOUSE_PRESSED, from.getX, from.getY))
        Event.fireEvent(tab, mouse(MouseEvent.MOUSE_DRAGGED, from.getX + 20, from.getY + 20))
        Event.fireEvent(tab, mouse(MouseEvent.MOUSE_DRAGGED, to.getX, to.getY))
        assert(r.getPseudoClassStates.contains(pseudo.Dragging))
        val targetBefore = r.indicatorTarget
        assert(targetBefore.nonEmpty)
        d.setMotion(DockMotion.Reduced)
        assert(r.getPseudoClassStates.contains(pseudo.Dragging))
        assertEquals(r.indicatorTarget, targetBefore)
        assertEquals(r.indicatorShown, targetBefore)
        assert(!r.indicatorAnimating)
        Event.fireEvent(tab, mouse(MouseEvent.MOUSE_DRAGGED, to.getX + 2, to.getY + 2))
        assertEquals(r.indicatorTarget, targetBefore)
        d.setMotion(DockMotion.Full)
        Event.fireEvent(tab, mouse(MouseEvent.MOUSE_RELEASED, to.getX + 2, to.getY + 2))
        assert(d.nodeOf(pane).get eq original)
        assertEquals(d.state.focused, Some(pane))
        val split = edit.parentOf(d.state, right.id).get._1
        assertEquals(split.axis, Axis.Vertical)
        assertEquals(split.cells(1).node.panes.map(_.id), Vector(pane))
        assert(!r.getPseudoClassStates.contains(pseudo.Dragging))
        assert(r.indicatorTarget.isEmpty && !r.indicatorAnimating)
      finally
        d.dispose(); stage.close()

  test("reduced-motion drag cancellation clears feedback and restores the divider cursor"):
    onFx:
      val (d, stage, _) = fixture()
      try
        d.setMotion(DockMotion.Full)
        val (r, g)  = start(d)
        val divider = d.view.lookup(".dock-divider")
        stage.getScene.setCursor(Cursor.CROSSHAIR)
        val point  = divider.localToScreen(2, 2)
        val before = d.state
        Event.fireEvent(divider, mouse(MouseEvent.MOUSE_PRESSED, point.getX, point.getY))
        Event.fireEvent(divider, mouse(MouseEvent.MOUSE_DRAGGED, point.getX + 25, point.getY))
        assert(stage.getScene.getCursor != Cursor.CROSSHAIR)
        d.setMotion(DockMotion.Reduced)
        assert(divider.asInstanceOf[DividerView].isResizing)
        assert(!r.indicatorAnimating && g.slideCount == 0)
        Event.fireEvent(
          d.view,
          new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)
        )
        assertEquals(stage.getScene.getCursor, Cursor.CROSSHAIR)
        assertEquals(d.state, before)
        assert(r.indicatorTarget.isEmpty && !r.indicatorAnimating && g.slideCount == 0)
        assert(!divider.asInstanceOf[DividerView].hoverPending)
      finally
        d.dispose(); stage.close()
end MotionSuite
