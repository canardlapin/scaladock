package legacy

import scaladock.fx.DockTheme

object LegacyThemeClient:
  // Exhaustive against the original three-case API; later compilation deliberately probes this.
  def label(theme: DockTheme): String = theme match
    case DockTheme.Dark => "dark"
    case DockTheme.Light => "light"
    case DockTheme.Custom(url) => url

  def main(args: Array[String]): Unit =
    val custom: DockTheme.Custom = DockTheme.Custom("synthetic:legacy")
    val DockTheme.Custom(url) = custom
    assert(url == "synthetic:legacy")
    assert(custom.ordinal == 2)
    assert(custom.isDark.isEmpty)
    assert(label(DockTheme.Dark) == "dark")
    assert(label(DockTheme.Light) == "light")
    assert(label(custom) == url)
    assert(!custom.applyWindowScheme(null))
    println("S1_LEGACY_CLIENT_OK constructor=true extractor=true ordinals=true knownValues=true")
