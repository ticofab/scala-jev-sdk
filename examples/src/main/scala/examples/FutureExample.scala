package examples

import io.github.ticofab.jev._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
 * The default: no effect library, no extra dependency. `JevClient.future` uses sttp's JDK-backed `Future` backend.
 *
 * Run with `TYPESAFE_API_KEY=... sbt "examples/runMain examples.FutureExample"`.
 */
object FutureExample {

  def main(args: Array[String]): Unit =
    JevClient.futureFromEnv match {
      case Left(error)   => println(s"could not build a client: ${error.getMessage}")
      case Right(client) =>
        val result: Future[Either[JevError, JevResponse]] =
          client.askEither(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration)

        try
          Await.result(result, 30.seconds) match {
            case Right(response) => println(Triage.report(response))
            case Left(error)     => println(s"the call failed: ${error.getMessage}")
          }
        finally Await.result(client.close(), 5.seconds)
    }
}
