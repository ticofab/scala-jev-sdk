# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[early SemVer](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html).

## [0.3.0] - 2026-10-15

Tracks [Jev 1.14](https://typesafe.ai/blog/jev-1-14). **Not binary compatible with 0.2.0**: anything compiled against
0.2.0 that constructs or copies a `JevRequest` must be recompiled. Source compiles unchanged, but `Question` and
`Answer` each gained a case, so exhaustive matches over them will warn until they handle it.

### Added

- **Images** (danger zone, `jev-1.13.0` and later): `JevRequest.withImage(ref, image)` attaches an `Image` built with
  `Image.png`, `Image.jpeg` or `Image.fromBase64`, and sends it as a base64 asset. Malformed base64, an image over
  16 MiB, an empty, oversized or unused reference, and a request over the body limit are rejected locally.
- **`Question.box`** (preview, `jev-1.14.0` / `jev-preview`), answered with a `BoxAnswer`: a `BoundingBox` in fractions
  of the image, with `width`, `height`, `area`, `center` and `inPixels`, plus a confidence. A box question without an
  image is rejected locally.
- **`JevRequest.withLongContext`** (danger zone), raising the context limits to 262k tokens for one request.
- **`JevError.PayloadTooLarge`** for 413. It used to be an `UnexpectedStatus`. Not retryable.
- **`ApiError.code`**, the machine-readable error code in the body when there is one: `detail.error_type` as the API
  sends it (such as `api_usage_error`), or a `code` field.

### Fixed

- `ApiError.detail` reads the message out of the API's `{"detail": {"error_type": ..., "message": ...}}` error body.
  It used to come back empty for it, since `detail` there is an object rather than a string.
- `Probability.toString` always prints a dot as the decimal separator. It followed the JVM's default locale, so on an
  Italian or German machine `0.92` printed as `0,9200`.

### Changed

- The `TypeSafe-Danger-Zone-Images` header is sent on any request carrying an image or a box question, and
  `TypeSafe-Danger-Zone-Long-Context` on any with long context on. A configured header of the same name is replaced,
  not duplicated.
- README: `jev-latest` moves to `jev-1.14.0` around 2026-10-21; pin a model where stable answers matter.

## [0.2.0] - 2026-10-11

Brings the client in line with what the API and TypeSafe's own SDKs do today. **Not binary compatible with 0.1.0**:
anything compiled against 0.1.0 that constructs or copies an `ApiError` case must be recompiled, and source that
destructures those cases positionally (`case RateLimited(retryAfter, body) =>`) needs the extra field.

### Added

- **`ApiError.requestId`**, read from the `x-typesafe-request-id` response header and appended to the error message,
  so the id TypeSafe support asks for is in your logs. Every `ApiError` case takes it as a last parameter defaulting
  to `None`.
- **`JevError.RequestTimeout`** for 408, retryable.
- Score questions with more than 10 levels are rejected locally, the API's documented maximum, instead of costing a
  round trip and a 422.

### Changed

- **The server's requested wait is honoured in every form it can take:** `retry-after-ms` first, then `Retry-After`
  as delay-seconds (fractions included) or as an HTTP date. A header that does not parse is ignored and the computed
  backoff applies, as before.
- 408 is retried by default, alongside 429, 529, 5xx and connection errors. It used to be an `UnexpectedStatus`.
- sttp client4 4.0.26 → 4.0.27.
- CI checks the source against Scala 3.10. The artifact is still published on 3.3 LTS.
- CI checks binary compatibility with [MiMa](https://github.com/lightbend/mima) against the previous release. The
  breaks listed above are the only ones it reports against 0.1.0, and they are recorded as deliberate.

## [0.1.0] - 2026-09-19

First release. A client for Jev, TypeSafe AI's System One model.

### Added

- **`JevClient[F]`**, parameterised only by the effect of the sttp 4 backend you hand it. Built with
  `JevClient.create(backend, config, retry)`, or `JevClient.create(backend)` to read the configuration from the
  environment. Both return `Either[JevError, JevClient[F]]`.
- **The three Jev primitives** — `Question.Noul`, `Question.Choice[A]` and `Question.Score` — answered in one round
  trip. Answers are looked up with the question value itself and typed accordingly, so a choice comes back as your
  own type rather than a string to re-parse; see `Question.choiceOf`.
- **`Probability`**, constrained to `[0.0, 1.0]` wherever the API returns one, so a probability can never be read as
  a percentage or an unbounded score.
- **`Content`** for the fields Jev accepts as either text or JSON: state, instructions, option descriptions and score
  levels. A `String` converts implicitly.
- **`JevError`** with a case for every documented status (400, 401, 403, 404, 422, 429, 529, 5xx) plus connection,
  decoding and local-validation failures, and `isRetryable` to separate the transient ones.
- **`RetryPolicy`** with exponential backoff, jitter and `Retry-After` support, on by default. Retrying is a
  constructor argument: `RetryPolicy.none` gives exactly one attempt per call.
- **`Sleeper[F]`** — how the client waits between attempts, the one thing an arbitrary `F` cannot supply on its own.
  Instances for `Future` and the blocking `Identity` are in scope; other effects need a one-liner, and need it even
  with `RetryPolicy.none`, since it is resolved at construction.
- **`JevConfig.fromEnv`**, reading `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` and `TYPESAFE_DEFAULT_MODEL`.
- **`listModels`**, over `GET /v1/models`.

### Design notes

- **Nothing throws.** Questions, options, requests and configurations are built with total constructors; problems are
  reported as values, all of them at once, by `JevRequest.validate` and `JevConfig.validate`. No call raises into the
  effect's error channel, `Answers.get` returns an `Option`, and there is no `unsafe`-flavoured escape hatch.
- **Failures surface early.** A request is checked locally before it is sent, so a malformed one costs no round trip
  and is never retried. A configuration is checked when a client is built from it, so a blank API key is a
  construction failure rather than a puzzling `401` later.
- **The backend is always the caller's.** Nothing is created behind your back, so there is no `close()` on the
  client, and no withers that could slip a client past validation — to change something, build another one with the
  same backend.
- **`Probability` has a private constructor**, reached through `Probability.clamp` (total) or `Probability.from`
  (`Either`), which keeps the range invariant without a constructor that can fail. `RetryPolicy` takes the other
  route and clamps out-of-range settings rather than rejecting them.
- **Scala 3 only, published on 3.3 LTS.** No 2.13 cross-build. LTS keeps the floor for consumers as low as it goes:
  TASTy is forward-compatible only, so building on a newer Scala would exclude LTS projects while buying nothing.
  The source is checked against 3.9 as well.
