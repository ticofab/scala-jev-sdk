package io.github.ticofab.jev

import scala.concurrent.duration._

/**
 * Exponential backoff with full-range jitter, applied to the failures [[retryOn]] accepts.
 *
 * The defaults retry the transient failures only -- 408, 429, 529, 5xx and connection errors -- three times, starting
 * at half a second. A 422 is never retried, because sending the same invalid request again will fail the same way.
 *
 * @param maxRetries
 *   attempts after the first one; `0` disables retrying
 * @param initialDelay
 *   wait before the first retry
 * @param maxDelay
 *   ceiling for the backoff
 * @param backoffFactor
 *   multiplier applied to the delay after each attempt
 * @param jitter
 *   fraction of the delay randomised away, in `[0.0, 1.0]`
 * @param retryOn
 *   which failures are worth another attempt
 * @param respectRetryAfter
 *   use the wait the server asks for in `retry-after-ms` or `Retry-After`, when it sends one
 */
final case class RetryPolicy(
    maxRetries: Int = 3,
    initialDelay: FiniteDuration = 500.millis,
    maxDelay: FiniteDuration = 8.seconds,
    backoffFactor: Double = 2.0,
    jitter: Double = 0.2,
    retryOn: JevError => Boolean = _.isRetryable,
    respectRetryAfter: Boolean = true
) {

  /**
   * The settings actually used, with out-of-range values brought back into range rather than rejected.
   *
   * A negative retry count means "no retries", a backoff factor below 1.0 would shrink the delay on every attempt, and
   * jitter only means anything as a fraction. Clamping keeps a careless policy harmless instead of making the
   * constructor a thing that can fail.
   */
  private[jev] val retries: Int   = math.max(0, maxRetries)
  private[jev] val factor: Double = if (backoffFactor.isNaN) 1.0 else math.max(1.0, backoffFactor)
  private[jev] val spread: Double = if (jitter.isNaN) 0.0 else math.min(1.0, math.max(0.0, jitter))

  /** How long to wait before attempt number `retry` (1-based), given what the server asked for. */
  def delayFor(
      retry: Int,
      retryAfter: Option[FiniteDuration],
      random: () => Double = () => math.random()
  ): FiniteDuration = {
    val server = if (respectRetryAfter) retryAfter else None
    server.map(d => if (d > maxDelay) maxDelay else d).getOrElse {
      val exponential = initialDelay.toMillis * math.pow(factor, (retry - 1).toDouble)
      val capped      = math.min(exponential, maxDelay.toMillis.toDouble)
      val jittered    = capped * spread * (random() * 2.0 - 1.0)
      math.max(0L, (capped + jittered).toLong).millis
    }
  }

  override def toString: String =
    if (retries == 0) "RetryPolicy(disabled)"
    else s"RetryPolicy(maxRetries=$retries, initialDelay=$initialDelay, maxDelay=$maxDelay)"
}

object RetryPolicy {

  /** Three retries with exponential backoff on transient failures. */
  val default: RetryPolicy = RetryPolicy()

  /** No retrying at all. */
  val none: RetryPolicy = RetryPolicy(maxRetries = 0)
}
