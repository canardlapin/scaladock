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
    latch.await(30, TimeUnit.SECONDS)

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
