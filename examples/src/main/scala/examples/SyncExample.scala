package examples

import io.github.ticofab.jev._

/**
 * The same call with a blocking backend. `Identity[A]` is `A`, so `ask` returns the response directly -- handy in
 * scripts, tests and batch jobs.
 *
 * Run with `TYPESAFE_API_KEY=... sbt "examples/runMain examples.SyncExample"`.
 */
object SyncExample {

  def main(args: Array[String]): Unit =
    JevClient.syncFromEnv match {
      case Left(error)   => println(s"could not build a client: ${error.getMessage}")
      case Right(client) =>
        try
          client.askEither(Triage.message, Triage.isUrgent, Triage.department, Triage.frustration) match {
            case Right(response) => println(Triage.report(response))
            case Left(error)     => println(s"the call failed: ${error.getMessage}")
          }
        finally client.close()
    }
}
