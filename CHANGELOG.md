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
