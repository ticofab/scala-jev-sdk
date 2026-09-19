package examples

import io.github.ticofab.jev._
import sttp.client4.DefaultFutureBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
 * The common case: a `Future`-based client over sttp's JDK backend, which needs no dependency beyond sttp core.
 *
 * Run with `TYPESAFE_API_KEY=... sbt "examples/runMain examples.FutureExample"`.
 */
object FutureExample {

  def main(args: Array[String]): Unit = {
    val backend = DefaultFutureBackend()

    val reported: Future[Unit] =
      JevClient.create(backend) match { // reads TYPESAFE_API_KEY
        case Left(error)   => Future.successful(println(s"could not build a client: ${error.getMessage}"))
        case Right(client) =>
          client
            .ask(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration)
            .map {
              case Right(response) => println(Triage.describe(response))
              case Left(error)     => println(s"the call failed: ${error.getMessage}")
            }
      }

    // The close is sequenced into the chain rather than hung off a `finally`. It is safe to do that here because
    // `ask` never fails its Future -- failures arrive as a Left inside it -- so nothing can short-circuit past this
    // flatMap. One Await, at the edge of main, because a JVM entry point has to block somewhere.
    Await.result(reported.flatMap(_ => backend.close()), 35.seconds)
  }
}
