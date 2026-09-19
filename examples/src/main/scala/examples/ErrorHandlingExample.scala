package examples

import io.github.ticofab.jev._

import scala.concurrent.duration._

/**
 * Failures as values, and retrying tuned per client.
 *
 * Run with `TYPESAFE_API_KEY=... sbt "examples/runMain examples.ErrorHandlingExample"`.
 */
object ErrorHandlingExample {

  def main(args: Array[String]): Unit = {
    val config = JevConfig.fromEnvUnsafe
      .withTimeout(10.seconds)
      .withRetry(RetryPolicy(maxRetries = 5, initialDelay = 250.millis, maxDelay = 10.seconds))

    val client = JevClient.sync(config)

    try
      client.askEither(Triage.message, Triage.isUrgent) match {
        case Right(response) =>
          println(s"urgency: ${response.answers(Triage.isUrgent).probability}")

        case Left(error: JevError.Validation) =>
          println(s"the request was rejected: ${error.detail.getOrElse(error.body)}")

        case Left(error: JevError.RateLimited) =>
          println(s"still rate limited after retrying; server asked for ${error.retryAfter}")

        case Left(error) if error.isRetryable =>
          println(s"transient failure that outlived the retry policy: ${error.getMessage}")

        case Left(error) =>
          println(s"giving up: ${error.getMessage}")
      }
    finally client.close()
  }
}
