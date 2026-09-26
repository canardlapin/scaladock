val scala3Version    = "3.7.0"
val javafxVersion    = "24.0.1"
// the demo runs on a current JavaFX: 25+ lets a scene's colour scheme drive native window
// decorations (dark title bars), which the fx module applies reflectively when available
val demoJavafxVersion = "27"
val scalafxVersion   = "24.0.0-R35"
val upickleVersion   = "4.1.0"
val munitVersion     = "1.1.1"
val munitScVersion   = "1.1.0"

ThisBuild / organization       := "io.github.bbuchsbaum"
ThisBuild / scalaVersion       := scala3Version
ThisBuild / versionScheme      := Some("early-semver")
ThisBuild / version            := "0.1.0-SNAPSHOT"
ThisBuild / licenses           := Seq(License.Apache2)
ThisBuild / homepage           := Some(url("https://github.com/bbuchsbaum/scaladock"))
ThisBuild / semanticdbEnabled  := true

val fxClassifier: String =
  (sys.props("os.name"), sys.props("os.arch")) match {
    case (n, "aarch64") if n.startsWith("Mac")   => "mac-aarch64"
    case (n, _) if n.startsWith("Mac")           => "mac"
    case (n, "aarch64") if n.startsWith("Linux") => "linux-aarch64"
    case (n, _) if n.startsWith("Linux")         => "linux"
    case _                                       => "win"
  }

val javafxModules = Seq("base", "graphics", "controls")

def javafxDeps(config: Configuration, version: String = javafxVersion): Seq[ModuleID] =
  javafxModules.map(m => ("org.openjfx" % s"javafx-$m" % version % config).classifier(fxClassifier))

val warnings = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wsafe-init"
)

val commonSettings = Seq(
  // Sources must relativize inside this module even when a consumer imports
  // the build from another working directory. Keep SemanticDB under target.
  scalacOptions ++= Seq("-sourceroot", baseDirectory.value.getAbsolutePath),
  scalacOptions ++= warnings,
  scalacOptions ++= (if (sys.env.contains("CI")) Seq("-Werror") else Seq.empty),
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"            % munitVersion   % Test,
    "org.scalameta" %% "munit-scalacheck" % munitScVersion % Test
  )
)

// The published libraries target JDK 22 (JavaFX 24's floor): compiled on a newer JDK, they must
// still link only against the Java 22 API. The demo alone runs on JavaFX 27 / JDK 25.
val libraryJdk = "22"

val librarySettings = Seq(
  scalacOptions ++= Seq("-java-output-version", libraryJdk)
)

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(librarySettings)
  .settings(
    name := "scaladock-core",
    scalacOptions += "-language:strictEquality",
    libraryDependencies += "com.lihaoyi" %% "upickle" % upickleVersion
  )

lazy val fx = project
  .in(file("modules/fx"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(librarySettings)
  .settings(
    name := "scaladock-fx",
    // Published POM must not carry a platform classifier: consumers supply natives.
    libraryDependencies ++= javafxDeps(Provided),
    libraryDependencies ++= javafxDeps(Test),
    Test / fork := true,
    Test / javaOptions ++= headlessFxOptions
  )

lazy val demo = project
  .in(file("modules/demo"))
  .dependsOn(fx)
  .settings(commonSettings)
  .settings(
    name           := "scaladock-demo",
    publish / skip := true,
    // ScalaFX is the demo's choice, not a dependency of the library (fx uses plain JavaFX)
    libraryDependencies += "org.scalafx" %% "scalafx" % scalafxVersion,
    libraryDependencies ++= javafxDeps(Compile, demoJavafxVersion),
    // the fx module's Provided 24.x must not win the demo's runtime classpath
    dependencyOverrides ++= javafxDeps(Compile, demoJavafxVersion),
    run / fork := true
  )

// Headless JavaFX for CI: software pipeline; on Linux CI the workflow wraps sbt in xvfb-run.
def headlessFxOptions: Seq[String] =
  if (sys.env.contains("CI")) Seq("-Dprism.order=sw", "-Djava.awt.headless=true", "-Dtestfx.headless=false")
  else Seq.empty

lazy val root = project
  .in(file("."))
  .aggregate(core, fx, demo)
  .settings(
    name           := "scaladock",
    publish / skip := true
  )
