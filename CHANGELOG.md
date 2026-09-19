# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[early SemVer](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html).

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
