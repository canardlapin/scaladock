package scaladock.fx

import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform

/** Boots the JavaFX toolkit once per JVM and runs test bodies on the FX thread. */
object FxFixture:

  private lazy val started: Boolean =
    val latch = new CountDownLatch(1)
    try
      Platform.startup(() => latch.countDown())
      Platform.setImplicitExit(false)
    catch case _: IllegalStateException => latch.countDown() // already running
    val ready = latch.await(30, TimeUnit.SECONDS)
    if ready then
      val runtime = System.getProperty("javafx.runtime.version")
      val classes = Vector(
        classOf[javafx.beans.Observable],
        classOf[javafx.scene.Scene],
        classOf[javafx.scene.control.Button]
      )
      val expected = Option(System.getProperty("scaladock.expectedJavafx"))
      expected.foreach: version =>
        require(runtime.takeWhile(_ != '+') == version, s"Expected JavaFX $version, got $runtime")
        require(
          Runtime.version().feature().toString == System.getProperty("scaladock.expectedJava"),
          "Unexpected qualification JDK"
        )
        classes.foreach: cls =>
          val module = cls.getModule
          require(module.isNamed, s"${cls.getName} is not in a named JavaFX module")
          require(
            module.getDescriptor.rawVersion.orElse("").takeWhile(_ != '+') == version,
            s"Incoherent JavaFX module: ${module.getName}"
          )
      println("RUNTIME_PROBE " + ujson.Obj(
        "java"   -> System.getProperty("java.version"),
        "javafx" -> runtime,
        "modules" -> ujson.Arr.from(classes.map(cls =>
          ujson.Obj(
            "name"     -> Option(cls.getModule.getName).getOrElse("unnamed"),
            "location" -> cls.getProtectionDomain.getCodeSource.getLocation.toString
          )
        ))
      ).render())
    ready

  /** Run `body` on the JavaFX application thread and wait for its result. */
  def onFx[A](body: => A): A =
    require(started, "JavaFX toolkit failed to start")
    if Platform.isFxApplicationThread then body
    else
      val latch                        = new CountDownLatch(1)
      var result: Either[Throwable, A] = null
      Platform.runLater { () =>
        result =
          try Right(body)
          catch case t: Throwable => Left(t)
        latch.countDown()
      }
      assert(latch.await(30, TimeUnit.SECONDS), "FX task timed out")
      result.fold(throw _, identity)
end FxFixture
