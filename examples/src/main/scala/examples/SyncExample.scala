package examples

import io.github.ticofab.jev._
import sttp.client4.DefaultSyncBackend

/**
 * The same call with a blocking backend.
 *
 * `Identity[A]` is `A`, so `ask` hands back the `Either` directly with no effect wrapper -- handy in scripts, tests and
 * batch jobs. There is no dedicated constructor for this: any sttp backend goes through `JevClient.create`, and
 * `Sleeper.identity` is in scope, so the default retries work here too -- they just block the calling thread, which is
 * what you asked for.
 *
 * Run with `TYPESAFE_API_KEY=... sbt "examples/runMain examples.SyncExample"`.
 */
object SyncExample {

  def main(args: Array[String]): Unit = {
    val backend = DefaultSyncBackend()

    // No try/finally: nothing between here and the close can throw. Building the client and asking the question
    // both hand back an Either, so the only way out of this block is the bottom of it.
    JevClient.create(backend) match {
      case Left(error)   => println(s"could not build a client: ${error.getMessage}")
      case Right(client) =>
        client.ask(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration) match {
          case Right(response) => println(Triage.describe(response))
          case Left(error)     => println(s"the call failed: ${error.getMessage}")
        }
    }

    backend.close()
  }
}
