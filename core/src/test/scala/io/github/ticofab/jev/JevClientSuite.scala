package io.github.ticofab.jev

import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{Header, StatusCode}
import sttp.shared.Identity

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._

class JevClientSuite extends JevSuite {

  private val config = JevConfig("test-key")

  /** Retries in tests should not actually wait. */
  private val noDelay: Sleeper[Identity] = Sleeper.fromFunction[Identity](_ => ())

  private def clientReturning(body: String, status: StatusCode = StatusCode.Ok): JevClient[Identity] =
    ok(JevClient.create(SyncBackendStub.whenAnyRequest.thenRespondAdjust(body, status), config, RetryPolicy.none))

  /** A backend that records what it was asked for, so the wire format can be inspected. */
  private def recording: (mutable.Buffer[sttp.client4.GenericRequest[?, ?]], sttp.client4.testing.SyncBackendStub) = {
    val seen    = mutable.Buffer.empty[sttp.client4.GenericRequest[?, ?]]
    val backend = SyncBackendStub
      .whenRequestMatches { request =>
        seen += request
        true
      }
      .thenRespondAdjust(Fixtures.fullResponse)
    (seen, backend)
  }

  test("a synchronous client returns the answer with no effect wrapper around it") {
    val client   = clientReturning(Fixtures.fullResponse)
    val response = ok(client.ask(Fixtures.state, Fixtures.isUrgent, Fixtures.department, Fixtures.frustration))

    assertEquals(response.answer(Fixtures.isUrgent).probability, Probability.clamp(0.92))
    assertEquals(response.answer(Fixtures.department).choice, "technical")
  }

  test("the request carries the documented path, auth header and body") {
    val (seen, backend) = recording
    val _               =
      ok(JevClient.create(backend, config.withModel("jev-1.13.0"), RetryPolicy.none))
        .ask(Fixtures.state, Fixtures.isUrgent)

    val request = seen.head
    assertEquals(request.uri.toString, "https://api.typesafe.ai/v1/systemone")
    assertEquals(request.method.method, "POST")
    assertEquals(request.header("Authorization"), Some("Bearer test-key"))
    assertEquals(request.contentType, Some("application/json"))

    val body = ujson.read(request.body.show.stripPrefix("string: "))
    assertEquals(body("model").str, "jev-1.13.0")
  }

  test("extra headers and a custom base url are honoured") {
    val (seen, backend) = recording
    val custom          = config
      .withBaseUrl(sttp.model.Uri.unsafeParse("https://jev.internal.test/proxy"))
      .withHeader("X-Tenant", "acme")

    val _ = ok(JevClient.create(backend, custom, RetryPolicy.none)).ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(seen.head.uri.toString, "https://jev.internal.test/proxy/v1/systemone")
    assertEquals(seen.head.header("X-Tenant"), Some("acme"))
  }

  test("each status maps onto its own error type") {
    def errorFor(status: Int, body: String = "{}"): JevError =
      err(clientReturning(body, StatusCode.unsafeApply(status)).ask(Fixtures.state, Fixtures.isUrgent))

    assert(errorFor(400).isInstanceOf[JevError.BadRequest])
    assert(errorFor(401).isInstanceOf[JevError.Authentication])
    assert(errorFor(403).isInstanceOf[JevError.PermissionDenied])
    assert(errorFor(404).isInstanceOf[JevError.NotFound])
    assert(errorFor(408).isInstanceOf[JevError.RequestTimeout])
    assert(errorFor(413).isInstanceOf[JevError.PayloadTooLarge])
    assert(errorFor(422).isInstanceOf[JevError.Validation])
    assert(errorFor(429).isInstanceOf[JevError.RateLimited])
    assert(errorFor(500).isInstanceOf[JevError.ServerError])
    assert(errorFor(529).isInstanceOf[JevError.Overloaded])
    assert(errorFor(418).isInstanceOf[JevError.UnexpectedStatus])
  }

  test("only transient failures are marked retryable") {
    assert(JevError.RequestTimeout("").isRetryable)
    assert(JevError.RateLimited(None, "").isRetryable)
    assert(JevError.Overloaded(None, "").isRetryable)
    assert(JevError.ServerError(503, "").isRetryable)
    assert(JevError.Connection(new RuntimeException("boom")).isRetryable)
    assert(!JevError.Validation("").isRetryable)
    assert(!JevError.PayloadTooLarge("").isRetryable)
    assert(!JevError.Authentication("").isRetryable)
    assert(!JevError.InvalidRequest(List("nope")).isRetryable)
  }

  test("an error body's message is surfaced") {
    val error = err(
      clientReturning("""{"message":"questions: field required"}""", StatusCode.UnprocessableEntity)
        .ask(Fixtures.state, Fixtures.isUrgent)
    )

    assertEquals(error.asInstanceOf[JevError.Validation].detail, Some("questions: field required"))
    assert(error.getMessage.contains("422"))
  }

  test("an error body's code is surfaced") {
    val error = err(
      clientReturning("""{"error":{"code":"max_tokens_exceeded","message":"too long"}}""", StatusCode.BadRequest)
        .ask(Fixtures.state, Fixtures.isUrgent)
    )

    assertEquals(error.asInstanceOf[JevError.BadRequest].code, Some("max_tokens_exceeded"))
    assertEquals(JevError.BadRequest("""{"code":"x"}""").code, Some("x"))
    assertEquals(JevError.BadRequest("not json").code, None)
  }

  test("the error body the API actually sends is read for its message and type") {
    // Captured from the live API: an image request sent without the danger-zone header.
    val body =
      """{"detail":{"error_type":"api_usage_error","message":"Images are experimental. Send the header TypeSafe-Danger-Zone-Images: true to use them."}}"""
    val error = JevError.BadRequest(body)

    assertEquals(error.code, Some("api_usage_error"))
    assert(error.detail.exists(_.startsWith("Images are experimental")), error.detail)
  }

  test("a request with images or a box question opts into the images danger zone") {
    val (seen, backend) = recording
    val client          = ok(JevClient.create(backend, config, RetryPolicy.none))

    val _ = client.run(Fixtures.dogRequest)
    val _ = client.ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(seen(0).header("TypeSafe-Danger-Zone-Images"), Some("true"))
    assertEquals(seen(1).header("TypeSafe-Danger-Zone-Images"), None)
    assertEquals(seen(1).header("TypeSafe-Danger-Zone-Long-Context"), None)
  }

  test("long context is opted into per request") {
    val (seen, backend) = recording
    val _               = ok(JevClient.create(backend, config, RetryPolicy.none))
      .run(JevRequest.of(Fixtures.state, Fixtures.isUrgent).withLongContext)

    assertEquals(seen.head.header("TypeSafe-Danger-Zone-Long-Context"), Some("true"))
    assertEquals(seen.head.header("TypeSafe-Danger-Zone-Images"), None)
  }

  test("a danger-zone header already in the config is not sent twice") {
    val (seen, backend) = recording
    val _ = ok(JevClient.create(backend, config.withHeader("typesafe-danger-zone-images", "true"), RetryPolicy.none))
      .run(Fixtures.dogRequest)

    assertEquals(seen.head.headers.count(_.is("TypeSafe-Danger-Zone-Images")), 1)
  }

  test("a failure is returned, never thrown") {
    val client = clientReturning("{}", StatusCode.Unauthorized)
    assert(client.ask(Fixtures.state, Fixtures.isUrgent).isLeft)
  }

  test("a transport failure becomes a retryable connection error") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespond(throw new java.net.ConnectException("refused"))
    val error   = err(ok(JevClient.create(backend, config, RetryPolicy.none)).ask(Fixtures.state, Fixtures.isUrgent))

    assert(error.isInstanceOf[JevError.Connection])
    assert(error.isRetryable)
  }

  test("a retryable failure is retried, and the eventual success is returned") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      if (attempts.incrementAndGet() < 3) ResponseStub.adjust("overloaded", StatusCode.TooManyRequests)
      else ResponseStub.adjust(Fixtures.fullResponse, StatusCode.Ok)
    }

    val client =
      ok(JevClient.create(backend, config, RetryPolicy(maxRetries = 3, initialDelay = 1.milli))(using noDelay))
    val response = ok(client.ask(Fixtures.state, Fixtures.isUrgent))

    assertEquals(attempts.get(), 3)
    assertEquals(response.answer(Fixtures.isUrgent).probability, Probability.clamp(0.92))
  }

  test("retries stop at maxRetries and the last failure is reported") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust("overloaded", StatusCode.TooManyRequests)
    }

    val client =
      ok(JevClient.create(backend, config, RetryPolicy(maxRetries = 2, initialDelay = 1.milli))(using noDelay))
    val error = err(client.ask(Fixtures.state, Fixtures.isUrgent))

    assertEquals(attempts.get(), 3) // the first attempt plus two retries
    assert(error.isInstanceOf[JevError.RateLimited])
  }

  test("a non-retryable failure is not retried") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust("nope", StatusCode.UnprocessableEntity)
    }

    val client =
      ok(JevClient.create(backend, config, RetryPolicy(maxRetries = 5, initialDelay = 1.milli))(using noDelay))
    val _ = client.ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(attempts.get(), 1)
  }

  /** The `retryAfter` a single 429 carrying `headers` comes back with. */
  private def retryAfterFrom(headers: Header*): Option[FiniteDuration] = {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      ResponseStub.adjust("slow down", StatusCode.TooManyRequests).copy(headers = headers)
    }
    val error = err(ok(JevClient.create(backend, config, RetryPolicy.none)).ask(Fixtures.state, Fixtures.isUrgent))
    error.asInstanceOf[JevError.RateLimited].retryAfter
  }

  test("Retry-After is parsed off a 429") {
    assertEquals(retryAfterFrom(Header("Retry-After", "7")), Some(7.seconds))
    assertEquals(retryAfterFrom(Header("Retry-After", "1.5")), Some(1500.millis))
  }

  test("retry-after-ms wins over Retry-After, and a broken one falls back to it") {
    assertEquals(retryAfterFrom(Header("retry-after-ms", "250"), Header("Retry-After", "7")), Some(250.millis))
    assertEquals(retryAfterFrom(Header("retry-after-ms", "soon"), Header("Retry-After", "7")), Some(7.seconds))
  }

  test("Retry-After is read as an HTTP date too") {
    val inAMinute = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
      .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60))
    val waited = retryAfterFrom(Header("Retry-After", inAMinute))

    assert(waited.exists(d => d > 55.seconds && d <= 60.seconds), s"got $waited")
    assertEquals(retryAfterFrom(Header("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT")), Some(Duration.Zero))
  }

  test("an unusable Retry-After is ignored rather than thrown on") {
    assertEquals(retryAfterFrom(Header("Retry-After", "later")), None)
    assertEquals(retryAfterFrom(Header("Retry-After", "-3")), None)
    assertEquals(retryAfterFrom(Header("Retry-After", "1e300")), None)
    assertEquals(retryAfterFrom(Header("retry-after-ms", "NaN")), None)
    assertEquals(retryAfterFrom(), None)
  }

  test("the request id is kept on the error and quoted in its message") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      ResponseStub
        .adjust("""{"detail":"boom"}""", StatusCode.InternalServerError)
        .copy(headers = Seq(Header("x-typesafe-request-id", "req_123")))
    }
    val error = err(ok(JevClient.create(backend, config, RetryPolicy.none)).ask(Fixtures.state, Fixtures.isUrgent))

    assertEquals(error.asInstanceOf[JevError.ApiError].requestId, Some("req_123"))
    assert(error.getMessage.endsWith("(request id: req_123)"), error.getMessage)
  }

  test("an error without a request id says nothing about one") {
    val error = err(clientReturning("{}", StatusCode.Unauthorized).ask(Fixtures.state, Fixtures.isUrgent))

    assertEquals(error.asInstanceOf[JevError.ApiError].requestId, None)
    assert(!error.getMessage.contains("request id"), error.getMessage)
  }

  test("a 408 is retried") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      if (attempts.incrementAndGet() < 2) ResponseStub.adjust("", StatusCode.RequestTimeout)
      else ResponseStub.adjust(Fixtures.fullResponse, StatusCode.Ok)
    }

    val client =
      ok(JevClient.create(backend, config, RetryPolicy(maxRetries = 3, initialDelay = 1.milli))(using noDelay))

    assert(client.ask(Fixtures.state, Fixtures.isUrgent).isRight)
    assertEquals(attempts.get(), 2)
  }

  test("listModels reads the account's models") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondAdjust(
      """{"data":[{"name":"jev-1.13.0","description":"System One","release_date":"2026-09-15"}]}"""
    )
    assertEquals(ok(ok(JevClient.create(backend, config, RetryPolicy.none)).listModels).map(_.name), Seq("jev-1.13.0"))
  }

  test("an invalid request never reaches the backend") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust(Fixtures.fullResponse, StatusCode.Ok)
    }

    val error = err(
      ok(JevClient.create(backend, config, RetryPolicy.none))
        .ask(Fixtures.state, Question.score("mood", "How?", "Only one"))
    )

    assertEquals(attempts.get(), 0)
    assertEquals(
      error.asInstanceOf[JevError.InvalidRequest].problems,
      List("score question 'mood' needs at least 2 levels, got 1")
    )
  }

  test("an invalid request is never retried") {
    val client = ok(
      JevClient.create(
        SyncBackendStub.whenAnyRequest.thenRespondAdjust(Fixtures.fullResponse),
        config,
        RetryPolicy(maxRetries = 5, initialDelay = 1.milli)
      )(using noDelay)
    )

    assert(client.ask(Fixtures.state, Question.choice("team", "Which?")).isLeft)
  }

  test("RetryPolicy.none makes exactly one attempt") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust("overloaded", StatusCode.TooManyRequests)
    }

    val _ = ok(JevClient.create(backend, config, RetryPolicy.none)).ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(attempts.get(), 1)
  }

  test("retries are on by default") {
    val attempts = new AtomicInteger(0)
    val backend  = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust("overloaded", StatusCode.TooManyRequests)
    }

    val _ = ok(JevClient.create(backend, config)(using noDelay)).ask(Fixtures.state, Fixtures.isUrgent)

    assertEquals(attempts.get(), RetryPolicy.default.retries + 1)
  }

  test("a nonsensical retry policy is clamped, not rejected") {
    val policy = RetryPolicy(maxRetries = -3, backoffFactor = 0.1, jitter = 5.0)

    assertEquals(policy.retries, 0)
    assertEqualsDouble(policy.factor, 1.0, 1e-9)
    assertEqualsDouble(policy.spread, 1.0, 1e-9)
  }

  test("a blank API key fails at construction, not on the first call") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondAdjust(Fixtures.fullResponse)
    val error   = err(JevClient.create(backend, JevConfig("   "), RetryPolicy.none))

    assertEquals(error.asInstanceOf[JevError.InvalidConfig].problems, List("the API key is empty"))
  }

  test("every configuration problem is reported at once") {
    val broken = JevConfig("", model = " ", timeout = Duration.Zero)

    assertEquals(
      broken.problems,
      List("the API key is empty", "the model name is empty", "the timeout must be positive, got 0 days")
    )
  }

  test("the config never prints the api key") {
    assert(!config.toString.contains("test-key"))
  }
}
