package io.github.ticofab.jev

import sttp.monad.MonadError

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}

/**
 * How to wait, in whatever effect the client runs in.
 *
 * Retrying needs a delay, and there is no way to wait generically in an arbitrary `F`. A `Sleeper[F]` is the one thing
 * this SDK asks you for beyond an sttp backend, and [[JevClient.create]] resolves it whether or not you retry -- so it
 * is needed even with [[RetryPolicy.none]]. Instances for `Future` and for the blocking `Identity` backend come for
 * free; anything else is a one-liner:
 *
 * {{{
 * // cats-effect
 * implicit val sleeper: Sleeper[IO] = Sleeper.fromFunction(d => IO.sleep(d))
 *
 * // Monix, ZIO, your own effect...
 * implicit val sleeper: Sleeper[Task] = Sleeper.fromFunction(d => Task.sleep(d))
 * }}}
 */
trait Sleeper[F[_]] {
  def sleep(duration: FiniteDuration): F[Unit]
}

object Sleeper {

  /** Builds a sleeper from any `FiniteDuration => F[Unit]`. */
  def fromFunction[F[_]](f: FiniteDuration => F[Unit]): Sleeper[F] =
    (duration: FiniteDuration) => f(duration)

  /** Retries immediately, with no backoff. Useful in tests. */
  def immediate[F[_]](implicit monad: MonadError[F]): Sleeper[F] =
    fromFunction(_ => monad.unit(()))

  /**
   * Blocks the calling thread.
   *
   * Correct for a synchronous backend, and for any effect whose `eval` runs on a pool meant for blocking work. Do not
   * hand this to a non-blocking runtime -- give it that runtime's own timer instead.
   */
  def blocking[F[_]](implicit monad: MonadError[F]): Sleeper[F] =
    fromFunction(duration => monad.eval(Thread.sleep(duration.toMillis)))

  /** Blocks the calling thread. The right instance for `DefaultSyncBackend`. */
  implicit val identity: Sleeper[sttp.shared.Identity] =
    fromFunction[sttp.shared.Identity](duration => Thread.sleep(duration.toMillis))

  /** Schedules on a shared daemon timer thread, so nothing is blocked while waiting. */
  implicit val future: Sleeper[Future] =
    fromFunction { duration =>
      val promise = Promise[Unit]()
      scheduler.schedule(
        new Runnable { def run(): Unit = promise.success(()) },
        duration.toMillis,
        TimeUnit.MILLISECONDS
      )
      promise.future
    }

  private lazy val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { (runnable: Runnable) =>
      val thread = new Thread(runnable, "jev-sdk-retry-timer")
      thread.setDaemon(true)
      thread
    }
}
