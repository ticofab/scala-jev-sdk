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

  def main(args: Array[String]): Unit = {
    val client = JevClient.futureFromEnv.fold(throw _, identity)

    val result: Future[JevResponse] =
      client.ask(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration)

    try println(Triage.report(Await.result(result, 30.seconds)))
    finally Await.result(client.close(), 5.seconds)
  }
}
