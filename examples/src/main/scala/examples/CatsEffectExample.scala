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
      JevClient.create(backend) match { // config from the environment
        case Left(error) =>
          IO.println(s"could not build a client: ${error.getMessage}").as(ExitCode.Error)

        case Right(client) =>
          client
            .ask(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration)
            .flatMap {
              case Right(response) => IO.println(Triage.describe(response)).as(ExitCode.Success)
              case Left(error)     => IO.println(s"the call failed: ${error.getMessage}").as(ExitCode.Error)
            }
      }
    }
}
