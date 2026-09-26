package scaladock.demo

import scalafx.Includes.*
import scalafx.application.JFXApp3
import scalafx.scene.Scene

/** The imaging-workstation showcase: living documentation and acceptance harness. */
object Main extends JFXApp3:
  override def start(): Unit =
    val app = DemoApp.build()
    stage = new JFXApp3.PrimaryStage:
      title = "scaladock — imaging workstation demo"
      width = 1280
      height = 840
      scene = new Scene:
        root = app.shell
end Main
