scalaVersion := "3.7.4"
Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror")
Compile / unmanagedJars := Seq(Attributed.blank(file("/Users/bbuchsbaum/code/scala/scaladock/docs/evidence/meridian-s1/baseline-artifacts/scaladock-fx_3-0.1.0-SNAPSHOT.jar")),Attributed.blank(file("/Users/bbuchsbaum/code/scala/scaladock/docs/evidence/meridian-s1/baseline-artifacts/scaladock-core_3-0.1.0-SNAPSHOT.jar")))
libraryDependencies ++= Seq("base","graphics","controls").map(m => ("org.openjfx" % s"javafx-$m" % "24.0.1").classifier("mac-aarch64"))

Compile / sources := Seq(baseDirectory.value / "src/main/scala/LegacyThemeClient.scala")
