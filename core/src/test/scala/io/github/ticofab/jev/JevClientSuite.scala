package io.github.ticofab.jev

import sttp.client4.testing.SyncBackendStub
import sttp.model.{Header, StatusCode}
import sttp.shared.Identity

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._

class JevClientSuite extends munit.FunSuite {

  private val config = JevConfig("test-key").withoutRetry

  /** Retries in tests should not actually wait. */
  private val noDelay: Sleeper[Identity] = Sleeper.fromFunction[Identity](_ => ())

  private def clientReturning(body: String, status: StatusCode = StatusCode.Ok): JevClient[Identity] =
    JevClient(SyncBackendStub.whenAnyRequest.thenRespondAdjust(body, status), config)

  test("a synchronous client returns the answer with no wrapper around it") {
    val client   = clientReturning(Fixtures.fullResponse)
    val response = client.ask(Fixtures.state, Fixtures.isUrgent, Fixtures.department, Fixtures.frustration)

    assertEquals(response.answers(Fixtures.isUrgent).probability, Probability.clamp(0.92))
    assertEquals(response.answers(Fixtures.department).choice, "technical")
  }

  test("the request carries the documented path, auth header and body") {
    val seen    = mutable.Buffer.empty[sttp.client4.GenericRequest[_, _]]
    val backend = SyncBackendStub
      .whenRequestMatches { request =>
        seen += request
        true
      }
      .thenRespondAdjust(Fixtures.fullResponse)

    JevClient(backend, config.withModel("jev-1.13.0")).ask(Fixtures.state, Fixtures.isUrgent)

    val request = seen.head
    assertEquals(request.uri.toString, "https://api.typesafe.ai/v1/systemone")
    assertEquals(request.method.method, "POST")
    assertEquals(request.header("Authorization"), Some("Bearer test-key"))
    assertEquals(request.contentType, Some("application/json"))

    val body = ujson.read(request.body.show.stripPrefix("string: "))
    assertEquals(body("model").str, "jev-1.13.0")
  }

  test("extra headers and a custom base url are honoured") {
    val seen    = mutable.Buffer.empty[sttp.client4.GenericRequest[_, _]]
    val backend = SyncBackendStub
      .whenRequestMatches { request =>
        seen += request
        true
      }
      .thenRespondAdjust(Fixtures.fullResponse)

    val custom = config
      .withBaseUrl(sttp.model.Uri.unsafeParse("https://jev.internal.test/proxy"))
      .withHeader("X-Tenant", "acme")
    JevClient(backend, custom).ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(seen.head.uri.toString, "https://jev.internal.test/proxy/v1/systemone")
    assertEquals(seen.head.header("X-Tenant"), Some("acme"))
  }

  test("each status maps onto its own error type") {
    def errorFor(status: Int, body: String = "{}"): JevError =
      clientReturning(body, StatusCode.unsafeApply(status))
        .askEither(Fixtures.state, Fixtures.isUrgent)
        .swap
        .getOrElse(fail("expected a failure"))

    assert(errorFor(400).isInstanceOf[JevError.BadRequest])
    assert(errorFor(401).isInstanceOf[JevError.Authentication])
    assert(errorFor(403).isInstanceOf[JevError.PermissionDenied])
    assert(errorFor(404).isInstanceOf[JevError.NotFound])
    assert(errorFor(422).isInstanceOf[JevError.Validation])
    assert(errorFor(429).isInstanceOf[JevError.RateLimited])
    assert(errorFor(500).isInstanceOf[JevError.ServerError])
    assert(errorFor(529).isInstanceOf[JevError.Overloaded])
    assert(errorFor(418).isInstanceOf[JevError.UnexpectedStatus])
  }

  test("only transient failures are marked retryable") {
    assert(JevError.RateLimited(None, "").isRetryable)
    assert(JevError.Overloaded(None, "").isRetryable)
    assert(JevError.ServerError(503, "").isRetryable)
    assert(JevError.Connection(new RuntimeException("boom")).isRetryable)
    assert(!JevError.Validation("").isRetryable)
    assert(!JevError.Authentication("").isRetryable)
  }

  test("an error body's message is surfaced") {
    val error = clientReturning("""{"message":"questions: field required"}""", StatusCode.UnprocessableEntity)
      .askEither(Fixtures.state, Fixtures.isUrgent)
      .swap
      .getOrElse(fail("expected a failure"))

    assertEquals(error.asInstanceOf[JevError.Validation].detail, Some("questions: field required"))
    assert(error.getMessage.contains("422"))
  }

  test("ask raises the failure in F, askEither hands it back") {
    val client = clientReturning("{}", StatusCode.Unauthorized)
    intercept[JevError.Authentication](client.ask(Fixtures.state, Fixtures.isUrgent))
    assert(client.askEither(Fixtures.state, Fixtures.isUrgent).isLeft)
  }

  test("a transport failure becomes a retryable connection error") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespond(throw new java.net.ConnectException("refused"))
    val error   = JevClient(backend, config)
      .askEither(Fixtures.state, Fixtures.isUrgent)
      .swap
      .getOrElse(fail("expected a failure"))

    assert(error.isInstanceOf[JevError.Connection])
    assert(error.isRetryable)
  }

  test("a retryable failure is retried, and the eventual success is returned") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      if (attempts.incrementAndGet() < 3)
        sttp.client4.testing.ResponseStub.adjust("overloaded", StatusCode.TooManyRequests)
      else sttp.client4.testing.ResponseStub.adjust(Fixtures.fullResponse, StatusCode.Ok)
    }

    val retrying = config.withRetry(RetryPolicy(maxRetries = 3, initialDelay = 1.milli))
    val response = JevClient(backend, retrying)(noDelay).ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(attempts.get(), 3)
    assertEquals(response.answers(Fixtures.isUrgent).probability, Probability.clamp(0.92))
  }

  test("retries stop at maxRetries and the last failure is reported") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      sttp.client4.testing.ResponseStub.adjust("overloaded", StatusCode.TooManyRequests)
    }

    val retrying = config.withRetry(RetryPolicy(maxRetries = 2, initialDelay = 1.milli))
    val error    = JevClient(backend, retrying)(noDelay)
      .askEither(Fixtures.state, Fixtures.isUrgent)
      .swap
      .getOrElse(fail("expected a failure"))

    assertEquals(attempts.get(), 3) // the first attempt plus two retries
    assert(error.isInstanceOf[JevError.RateLimited])
  }

  test("a non-retryable failure is not retried") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      sttp.client4.testing.ResponseStub.adjust("nope", StatusCode.UnprocessableEntity)
    }

    JevClient(backend, config.withRetry(RetryPolicy(maxRetries = 5, initialDelay = 1.milli)))(noDelay)
      .askEither(Fixtures.state, Fixtures.isUrgent)

    assertEquals(attempts.get(), 1)
  }

  test("Retry-After is parsed off a 429") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      sttp.client4.testing.ResponseStub
        .adjust("slow down", StatusCode.TooManyRequests)
        .copy(headers = Seq(Header("Retry-After", "7")))
    }

    val error = JevClient(backend, config).askEither(Fixtures.state, Fixtures.isUrgent).swap.getOrElse(fail("expected"))
    assertEquals(error.asInstanceOf[JevError.RateLimited].retryAfter, Some(7.seconds))
  }

  test("listModels reads the account's models") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondAdjust(
      """{"data":[{"name":"jev-1.13.0","description":"System One","release_date":"2026-09-15"}]}"""
    )
    assertEquals(JevClient(backend, config).listModels.map(_.name), Seq("jev-1.13.0"))
  }

  test("an invalid request never reaches the backend") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      sttp.client4.testing.ResponseStub.adjust(Fixtures.fullResponse, StatusCode.Ok)
    }

    val error = JevClient(backend, config)
      .askEither(Fixtures.state, Question.score("mood", "How?", "Only one"))
      .swap
      .getOrElse(fail("expected a failure"))

    assertEquals(attempts.get(), 0)
    assertEquals(
      error.asInstanceOf[JevError.InvalidRequest].problems,
      List("score question 'mood' needs at least 2 levels, got 1")
    )
  }

  test("an invalid request is never retried") {
    val retrying = config.withRetry(RetryPolicy(maxRetries = 5, initialDelay = 1.milli))
    val client   = JevClient(SyncBackendStub.whenAnyRequest.thenRespondAdjust(Fixtures.fullResponse), retrying)(noDelay)

    assert(client.askEither(Fixtures.state, Question.choice("team", "Which?")).isLeft)
  }

  test("a nonsensical retry policy is clamped, not rejected") {
    val policy = RetryPolicy(maxRetries = -3, backoffFactor = 0.1, jitter = 5.0)

    assertEquals(policy.retries, 0)
    assertEqualsDouble(policy.factor, 1.0, 1e-9)
    assertEqualsDouble(policy.spread, 1.0, 1e-9)
  }

  test("the config never prints the api key") {
    assert(!config.toString.contains("test-key"))
  }
}
