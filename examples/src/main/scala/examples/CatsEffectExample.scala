package examples

import cats.effect.{ExitCode, IO, IOApp}
import io.github.ticofab.jev._
import sttp.client4.httpclient.cats.HttpClientCatsBackend

/**
 * Plugging in a different effect: the client is `JevClient[IO]` purely because the backend is, and retries use
 * cats-effect's own timer rather than blocking a thread.
 *
 * Run with `TYPESAFE_API_KEY=... sbt "examples/runMain examples.CatsEffectExample"`.
 */
object CatsEffectExample extends IOApp {

  /** Retries wait on cats-effect's own timer instead of blocking a thread. */
  implicit private val sleeper: Sleeper[IO] = Sleeper.fromFunction[IO](IO.sleep)

  def run(args: List[String]): IO[ExitCode] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      for {
        config <- IO.fromEither(JevConfig.fromEnv)
        client = JevClient(backend, config)
        response <- client.ask(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration)
        _        <- IO.println(Triage.report(response))
      } yield ExitCode.Success
    }
}
