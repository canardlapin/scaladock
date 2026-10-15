package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class ThemeSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.theme.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def firstFill(r: Region): Color =
    r.getBackground.getFills.get(0).getFill.asInstanceOf[Color]

  test("the drop indicator resolves its colors from theme variables, not hardcoded values"):
    onFx:
      val dock  = Dock(factories, initial = LayoutState.of(group(TxtPane(Txt("a")).titled("a"))))
      val scene = new Scene(dock.view, 800, 600)
      assert(scene != null)
      val region = dock.view.asInstanceOf[DockRegion]
      region.showIndicator(Rect(10, 10, 100, 100))
      dock.view.applyCss()
      dock.view.layout()

      val indicator = region.lookup(".dock-drop-indicator").asInstanceOf[Region]
      val darkFill  = firstFill(indicator)
      // -dock-accent-soft (dark): #3574f04d — a translucent blue, decidedly not black
      assert(darkFill.getOpacity < 0.99, s"expected translucent accent fill, got $darkFill")
      assert(darkFill.getBlue > darkFill.getRed, s"expected a blue accent, got $darkFill")

      dock.setTheme(DockTheme.Light)
      dock.view.applyCss()
      val lightFill = firstFill(indicator)
      assertNotEquals(lightFill, darkFill, "the light theme must restyle the indicator")

  test("the ghost popup carries the variable-bearing marker class"):
    onFx:
      val ghost = GhostPopup("title", None, None)
      val box   = ghost.getContent.get(0).asInstanceOf[Region]
      assert(box.getStyleClass.contains("dock"), "ghost must resolve theme variables itself")
      assert(box.getStyleClass.contains("dock-ghost"))

  private def scheme(scene: Scene): Option[String] =
    try
      val prefs = classOf[Scene].getMethod("getPreferences").invoke(scene)
      Some(Class.forName(
        "javafx.scene.Scene$Preferences"
      ).getMethod("getColorScheme").invoke(prefs).toString)
    catch case scala.util.control.NonFatal(_) | _: LinkageError => None

  private def withCustomSheets(body: (String, String) => Unit): Unit =
    val dark  = java.nio.file.Files.createTempFile("light-name-dark-palette", ".css")
    val light = java.nio.file.Files.createTempFile("dark-name-light-palette", ".css")
    try
      java.nio.file.Files.writeString(
        dark,
        ".dock { -dock-base: #151923; -dock-surface: #252c3a; -dock-text: #edf0f5; -dock-text-strong: #edf0f5; }"
      ): Unit
      java.nio.file.Files.writeString(
        light,
        ".dock { -dock-base: #ffffff; -dock-surface: #f1f3f7; -dock-text: #202530; -dock-text-strong: #202530; }"
      ): Unit
      body(dark.toUri.toString, light.toUri.toString)
    finally
      val _ = java.nio.file.Files.deleteIfExists(dark)
      val _ = java.nio.file.Files.deleteIfExists(light)

  test("legacy Custom constructor, extractor, ordinals and unknown metadata remain intact"):
    val old: DockTheme.Custom = DockTheme.Custom("dark-looking-name.css")
    val DockTheme.Custom(url) = old
    assertEquals(url, "dark-looking-name.css")
    assertEquals(old.isDark, None)
    assertEquals(DockTheme.Dark.ordinal, 0)
    assertEquals(DockTheme.Light.ordinal, 1)
    assertEquals(old.ordinal, 2)
    assertEquals(DockTheme.Dark.isDark, Some(true))
    assertEquals(DockTheme.Light.isDark, Some(false))
    assertEquals(DockTheme.CustomWithScheme("light-name.css", dark = true).isDark, Some(true))
    assertEquals(DockTheme.CustomWithScheme("dark-name.css", dark = false).isDark, Some(false))

  test("custom scheme metadata affects equality but preserves stylesheet order"):
    withCustomSheets: (dark, _) =>
      val knownDark  = DockTheme.CustomWithScheme(dark, dark = true)
      val knownLight = DockTheme.CustomWithScheme(dark, dark = false)
      assertNotEquals(knownDark, knownLight)
      assertEquals(knownDark.stylesheets.last, dark)
      assertEquals(knownLight.stylesheets, knownDark.stylesheets)
      assertEquals(DockTheme.Custom(dark).stylesheets, knownDark.stylesheets)

  test("main scene schemes are explicit host hooks with legacy no-op and platform fallback"):
    onFx:
      val scene    = new Scene(new Label("synthetic host"))
      val supports = classOf[Scene].getMethods.exists(_.getName == "getPreferences")
      assertEquals(
        DockTheme.CustomWithScheme("not-loaded.css", dark = true).applyWindowScheme(scene),
        supports
      )
      assertEquals(scheme(scene), Option.when(supports)("DARK"))
      assertEquals(DockTheme.Custom("light-looking-name.css").applyWindowScheme(scene), false)
      assertEquals(
        scheme(scene),
        Option.when(supports)("DARK"),
        "legacy unknown helper remains a no-op"
      )
      assertEquals(
        DockTheme.CustomWithScheme("not-loaded.css", dark = false).applyWindowScheme(scene),
        supports
      )
      assertEquals(scheme(scene), Option.when(supports)("LIGHT"))
      assertEquals(DockTheme.Dark.applyWindowScheme(scene), supports)
      assertEquals(DockTheme.Custom("unknown.css").applyWindowSchemeOrPlatform(scene), supports)
      if supports then
        assertEquals(
          scheme(scene),
          Some(javafx.application.Platform.getPreferences.getColorScheme.toString)
        )

  test(
    "known custom schemes reach existing, new and restored floats without changing main ownership or pane identity"
  ):
    onFx:
      withCustomSheets: (dark, light) =>
        val initial = LayoutState.of(row(
          TxtPane(Txt("main")).titled("main"),
          TxtPane(Txt("float")).titled("float")
        ))
        val dock      = Dock(factories, initial = initial)
        val stage     = new javafx.stage.Stage
        val mainScene = new Scene(dock.view, 800, 600)
        stage.setScene(mainScene)
        stage.show()
        try
          val supported  = classOf[Scene].getMethods.exists(_.getName == "getPreferences")
          val mainBefore = scheme(mainScene)
          val target     = dock.state.panes.find(_.title == "float").get
          val node       = dock.nodeOf(target.id).get
          dock.popOut(dock.state.groupOf(target.id).get.id)
          val floating = node.getScene
          dock.setTheme(DockTheme.CustomWithScheme(dark, dark = true))
          assertEquals(scheme(floating), Option.when(supported)("DARK"))
          assertEquals(scheme(mainScene), mainBefore, "dock does not own the host's main scheme")
          val stateBefore = dock.state
          dock.setTheme(DockTheme.CustomWithScheme(light, dark = false))
          assertEquals(scheme(floating), Option.when(supported)("LIGHT"))
          assertEquals(dock.state, stateBefore, "appearance does not mutate layout/pane state")
          assert(dock.nodeOf(target.id).get eq node)
          dock.dockBack(dock.state.floating.head.window)
          dock.setTheme(DockTheme.CustomWithScheme(dark, dark = true))
          dock.popOut(dock.state.groupOf(target.id).get.id)
          assertEquals(
            scheme(node.getScene),
            Option.when(supported)("DARK"),
            "new float inherits current metadata"
          )
          val saved = dock.save()
          dock.dockAllBack()
          assertEquals(dock.load(saved), Right(()))
          assertEquals(
            scheme(node.getScene),
            Option.when(supported)("DARK"),
            "restored float inherits metadata"
          )
          dock.setTheme(DockTheme.Custom(light))
          if supported then
            assertEquals(
              scheme(node.getScene),
              Some(javafx.application.Platform.getPreferences.getColorScheme.toString),
              "unknown owned window follows platform"
            )
          assert(dock.nodeOf(target.id).get eq node)
        finally
          dock.dispose()
          stage.close()
        end try

end ThemeSuite
