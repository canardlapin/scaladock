package scaladock.demo

import java.nio.file.{Files, Path}
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.geometry.Pos
import javafx.scene.layout.{BorderPane, HBox, Region, VBox}
import javafx.scene.shape.Circle
import scaladock.*
import scaladock.fx.{Dock, DockTheme}

/** The window chrome around the dock: a slim app bar and a status bar, themed by the same `-dock-*`
  * variables as the dock (the root carries the `dock` class and the theme's sheets).
  */
private[demo] object Shell:

  private val layoutFile = Path.of(sys.props("user.home"), ".scaladock-demo-layout.json")

  private def button(text: String, icon: Node): Button =
    val b = Button(text, icon)
    b.getStyleClass.setAll("app-button")
    b.setFocusTraversable(false)
    b

  private def bar(classes: String, children: Node*): HBox =
    val h = HBox(children*)
    h.getStyleClass.add(classes): Unit
    h

  def apply(
      dock: Dock,
      session: Session,
      layout: LayoutState,
      newViewer: () => PaneDef
  ): BorderPane =
    val shell  = BorderPane(dock.view)
    val status = label("", "status-text")

    // -- app bar ---------------------------------------------------------------------------------

    val save = button("Save layout", Icons.save())
    save.setOnAction: _ =>
      Files.writeString(layoutFile, ujson.write(dock.save(), indent = 2)): Unit
      status.setText(s"layout saved to ~/${layoutFile.getFileName}")

    val load = button("Load layout", Icons.load())
    load.setOnAction: _ =>
      if Files.exists(layoutFile) then
        dock.load(ujson.read(Files.readString(layoutFile))) match
          case Left(err) => status.setText(s"load failed: ${err.message}")
          case Right(()) => status.setText("layout restored")
      else status.setText("no saved layout yet")

    def restore(): Unit =
      dock.update(_ => layout)
      status.setText("layout reset")

    val reset = button("Reset", Icons.reset())
    reset.setOnAction(_ => restore())

    val chip = bar("palette-chip", Icons.grip(), label("New viewer", "chip-text"))
    dock.dragSource(chip)(newViewer): Unit

    val theme = button("", Icons.sun())

    def syncTheme(): Unit =
      shell.getStylesheets.setAll((dock.theme.stylesheets ++ DemoStyle.sheets)*)
      val dark = dock.theme != DockTheme.Light
      // the label names the action: the theme you switch *to*
      theme.setText(if dark then "Light" else "Dark")
      theme.setGraphic(if dark then Icons.sun() else Icons.moon())
      // the native title bar follows the theme too (JavaFX 25+)
      Option(shell.getScene).foreach(dock.theme.applyWindowScheme(_): Unit)

    theme.setOnAction: _ =>
      dock.setTheme(if dock.theme == DockTheme.Light then DockTheme.Dark else DockTheme.Light)
      syncTheme()

    val divider = Region()
    divider.getStyleClass.add("app-sep"): Unit

    // wordmark and subtitle share a baseline; the bar centres everything else
    val wordmark = bar(
      "wordmark",
      label("scaladock", "brand-name"),
      label("imaging workstation demo", "brand-sub")
    )
    wordmark.setAlignment(Pos.BASELINE_LEFT)

    val appBar = bar(
      "app-bar",
      Icons.brand(),
      wordmark,
      divider,
      save,
      load,
      reset,
      spacer(),
      chip,
      theme
    )

    // fill-height would stretch the wordmark row to 36px and pin its baseline to the top: keep
    // every child at its own height, centred, so all app-bar text shares one baseline
    appBar.setFillHeight(false)

    // -- status bar --------------------------------------------------------------------------------

    val dot = Circle(3.5)
    dot.getStyleClass.add("status-dot"): Unit
    val counts = label("", "status-text")
    val ras    = label("", "status-text", "status-ras", "mono")
    ras.textProperty.bind(session.ras)

    def recount(s: LayoutState): Unit =
      val viewers                      = s.panes.count(_.content.tpe eq DemoApp.Viewer)
      val panels                       = s.panes.size
      def plural(n: Int, word: String) = s"$n $word${if n == 1 then "" else "s"}"
      counts.setText(s"study-7 · ${plural(viewers, "viewer")} · ${plural(panels, "panel")}")

    val statusBar = bar("status-bar", dot, counts, status, spacer(), ras)

    // -- empty state: the framework's look, plus a way back ---------------------------------------

    val openViewer = button("New viewer", Icons.plus())
    openViewer.getStyleClass.add("app-button-primary"): Unit
    openViewer.setOnAction(_ => dock.open(newViewer()): Unit)
    val restoreButton = button("Restore default layout", Icons.reset())
    restoreButton.getStyleClass.add("app-button-secondary"): Unit
    restoreButton.setOnAction(_ => restore())

    val emptyIcon = Region()
    emptyIcon.getStyleClass.addAll("dock-icon", "empty-layout"): Unit
    val actions = bar("demo-empty-actions", openViewer, restoreButton)
    actions.setAlignment(Pos.CENTER)
    val placeholder = VBox(
      emptyIcon,
      label("No open panels", "dock-empty-title"),
      label("Drag a tab here, open a scan from the Explorer, or start fresh", "dock-empty-hint"),
      actions
    )
    placeholder.getStyleClass.addAll("dock-empty", "demo-empty"): Unit
    placeholder.setAlignment(Pos.CENTER)
    placeholder.setVisible(false) // the dock shows it only while the layout is empty
    placeholder.setManaged(false)
    DemoStyle.adopt(placeholder)
    dock.setPlaceholder(Some(placeholder))

    // -- assembly ----------------------------------------------------------------------------------

    shell.getStyleClass.addAll("dock", "demo-shell"): Unit
    shell.setTop(appBar)
    shell.setBottom(statusBar)
    syncTheme()
    recount(dock.state)
    val onEvent: DockEvent => Unit =
      case DockEvent.LayoutChanged(s) => recount(s)
      case _                          => ()
    dock.events.subscribe(onEvent): Unit
    shell.sceneProperty.addListener((_, _, scene) =>
      if scene != null then dock.theme.applyWindowScheme(scene): Unit
    )
    // follow every theme change, whether from this button or from any other host code
    val _ = dock.themeChanges.subscribe(_ => syncTheme())
    shell
  end apply
end Shell
