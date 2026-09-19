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

    JevClient.create(backend) match { // reads TYPESAFE_API_KEY
      case Left(error)   => println(s"could not build a client: ${error.getMessage}")
      case Right(client) =>
        val result: Future[Either[JevError, JevResponse]] =
          client.ask(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration)

        try
          Await.result(result, 30.seconds) match {
            case Right(response) => println(Triage.describe(response))
            case Left(error)     => println(s"the call failed: ${error.getMessage}")
          }
        finally Await.result(backend.close(), 5.seconds)
    }
  }
}
