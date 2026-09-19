# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[early SemVer](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html).

## [Unreleased]

### Added

- `JevClient[F]`, effect-agnostic over any sttp 4 backend, with `Future` and blocking `Identity` constructors.
- The three Jev primitives as `Question.Noul`, `Question.Choice[A]` and `Question.Score`, with answers looked up by
  the question value and typed accordingly.
- `Question.choiceOf` for choices whose options are values of the caller's own type.
- `Probability`, constrained to `[0.0, 1.0]` everywhere the API returns one.
- `Content` for the string-or-JSON fields: state, instructions, option descriptions and score levels.
- `JevError` covering every documented status (401, 403, 404, 400, 422, 429, 529, 5xx) plus connection and decoding
  failures, with `isRetryable`.
- `RetryPolicy` with exponential backoff, jitter and `Retry-After` support, and `Sleeper` to wait in any effect.
- `JevConfig.fromEnv`, reading `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` and `TYPESAFE_DEFAULT_MODEL`.
- `listModels`, over `GET /v1/models`.

### Design notes

- Nothing in the construction path throws. Questions, options, requests, configs and retry policies are built with
  total constructors, and `JevRequest.validate` reports every problem at once as `JevError.InvalidRequest`. The
  client checks a request locally before sending it, so a malformed one costs no round trip.
- `Probability` has a private constructor, reached through `Probability.clamp` (total) or `Probability.from`
  (`Either`), so the `[0.0, 1.0]` invariant holds without a constructor that can fail.
- `RetryPolicy` clamps out-of-range settings instead of rejecting them.
- `Content.parse` returns `Either[String, Content]` rather than throwing on malformed JSON.
