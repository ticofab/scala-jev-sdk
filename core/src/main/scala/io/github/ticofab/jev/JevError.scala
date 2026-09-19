package io.github.ticofab.jev

import scala.concurrent.duration.FiniteDuration

/**
 * Everything this SDK can fail with.
 *
 * `JevError` is a `RuntimeException`, so it travels naturally in whichever effect you plug in: a failed `Future`, a
 * `Left` from [[JevClient.askEither]], a raised `IO`. [[isRetryable]] marks the failures that are worth trying again,
 * and is what the built-in [[RetryPolicy]] keys off.
 */
sealed abstract class JevError(message: String, cause: Throwable) extends RuntimeException(message, cause) {

  /** True for transient failures: rate limits, overload, 5xx, and connection problems. */
  def isRetryable: Boolean = false
}

object JevError {

  /** An error the API returned, carrying its HTTP status and raw body. */
  sealed abstract class ApiError(val status: Int, val body: String, message: String) extends JevError(message, null) {

    /** The `message` or `detail` field of the error body, when it has one. */
    def detail: Option[String] = ApiError.detailOf(body)
  }

  private object ApiError {
    def detailOf(body: String): Option[String] =
      scala.util.Try(ujson.read(body)).toOption.flatMap { json =>
        val candidates = Seq(
          json.objOpt.flatMap(_.get("message")),
          json.objOpt.flatMap(_.get("detail")),
          json.objOpt.flatMap(_.get("error")).flatMap(_.objOpt).flatMap(_.get("message"))
        )
        candidates.flatten.flatMap(_.strOpt).headOption
      }

    def describe(status: Int, what: String, body: String): String = {
      val trimmed = body.trim.take(500)
      val suffix  = if (trimmed.isEmpty) "" else s": $trimmed"
      s"[$status] $what$suffix"
    }
  }

  /**
   * The request was rejected before it ever left the process.
   *
   * Everything this SDK can check locally -- empty or duplicated question names, a choice with no options, a score with
   * fewer than two levels -- is reported here, as a list so one call tells you about every problem rather than only the
   * first.
   */
  final case class InvalidRequest(problems: List[String])
      extends JevError(
        if (problems.sizeIs == 1) s"invalid request: ${problems.head}"
        else problems.mkString(s"invalid request (${problems.size} problems): ", "; ", ""),
        null
      )

  /** No API key was configured, and `TYPESAFE_API_KEY` is not set. */
  final case class MissingApiKey(env: String)
      extends JevError(s"no API key configured; pass one to JevConfig or set $env", null)

  /** 401 -- the API key is missing or not valid. */
  final case class Authentication(override val body: String)
      extends ApiError(401, body, ApiError.describe(401, "invalid or missing API key", body))

  /** 403 -- the key is valid but not allowed to do this. */
  final case class PermissionDenied(override val body: String)
      extends ApiError(403, body, ApiError.describe(403, "permission denied", body))

  /** 404 -- no such endpoint or resource. */
  final case class NotFound(override val body: String)
      extends ApiError(404, body, ApiError.describe(404, "not found", body))

  /** 400 -- the request was malformed. */
  final case class BadRequest(override val body: String)
      extends ApiError(400, body, ApiError.describe(400, "bad request", body))

  /** 422 -- the request was well-formed but failed validation; the body names the offending field. */
  final case class Validation(override val body: String)
      extends ApiError(422, body, ApiError.describe(422, "request validation failed", body))

  /** 429 -- rate limited. Retryable; honours `Retry-After` when the response carries one. */
  final case class RateLimited(retryAfter: Option[FiniteDuration], override val body: String)
      extends ApiError(429, body, ApiError.describe(429, "rate limit exceeded", body)) {
    override def isRetryable: Boolean = true
  }

  /** 529 -- the service is overloaded. Retryable. */
  final case class Overloaded(retryAfter: Option[FiniteDuration], override val body: String)
      extends ApiError(529, body, ApiError.describe(529, "service overloaded", body)) {
    override def isRetryable: Boolean = true
  }

  /** Any other 5xx. Retryable. */
  final case class ServerError(override val status: Int, override val body: String)
      extends ApiError(status, body, ApiError.describe(status, "server error", body)) {
    override def isRetryable: Boolean = true
  }

  /** A status this SDK does not recognise. */
  final case class UnexpectedStatus(override val status: Int, override val body: String)
      extends ApiError(status, body, ApiError.describe(status, "unexpected status", body))

  /** The request never got an answer: DNS, TLS, timeouts, a dropped socket. Retryable. */
  final case class Connection(underlying: Throwable)
      extends JevError(s"could not reach the Jev API: ${underlying.getMessage}", underlying) {
    override def isRetryable: Boolean = true
  }

  /** A 2xx response that this SDK could not read. */
  final case class Decoding(reason: String, body: String)
      extends JevError(s"could not decode the Jev response: $reason", null)

}
