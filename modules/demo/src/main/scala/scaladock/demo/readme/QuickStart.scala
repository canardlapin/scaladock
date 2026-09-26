package scaladock.demo.readme

// The README's quick start, verbatim below this line: the demo build compiles it, so the
// front-page example cannot silently rot. Run it with `sbt "demo/runMain scaladock.demo.readme.hello"`.

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.TextArea
import javafx.stage.Stage
import scaladock.*
import scaladock.dsl.*
import scaladock.fx.*
import upickle.default.ReadWriter

// 1. The state a pane saves and restores
case class Note(text: String) derives ReadWriter
val NoteType = PaneType[Note]("app.note")

// 2. The pane's view: any JavaFX node, plus how to snapshot its state
class NoteView(start: Note) extends PaneView[Note]:
  private val area = TextArea(start.text)
  def node         = area
  def snapshot()   = Note(area.getText)

@main def hello(): Unit = Platform.startup: () =>
  val factories = PaneFactories.empty.register(NoteType)(s => NoteView(s))

  // 3. The layout, written as a picture of itself
  val layout = LayoutState.of(
    row(
      NoteType(Note("notes.txt")).titled("Files") sized 240.px,
      column(
        group(NoteType(Note("Hello")).titled("Draft"), NoteType(Note("Todo")).titled("Todo")),
        NoteType(Note("ready")).titled("Log") sized 160.px
      )
    )
  )

  // 4. One Dock, dropped into any scene
  val dock = Dock(factories, initial = layout)
  dock.setTheme(DockTheme.Light) // matches JavaFX's default (light) controls
  val stage = Stage()
  stage.setScene(Scene(dock.view, 1100, 700))
  stage.show()
