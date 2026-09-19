# scala-jev-sdk

A Scala client for [Jev](https://docs.typesafe.ai), TypeSafe AI's System One model — the model that answers typed
questions about a piece of state instead of generating text.

No effect system. The client is parameterised over whichever [sttp](https://sttp.softwaremill.com) backend you hand
it, so it speaks `Future` out of the box, blocking `Identity` if you want no wrapper at all, and cats-effect, ZIO,
Monix or Pekko if that is what your codebase already runs on.

[![CI](https://github.com/ticofab/scala-jev-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/ticofab/scala-jev-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ticofab/scala-jev-sdk_3.svg)](https://central.sonatype.com/artifact/io.github.ticofab/scala-jev-sdk_3)

```scala
libraryDependencies += "io.github.ticofab" %% "scala-jev-sdk" % "<version>"
```

Scala 2.13 and 3.3+, JDK 17+. The only dependencies are `sttp-client4-core` and `upickle`.

---

## What Jev is, in one minute

Jev answers three kinds of question about some `state` you give it, and returns probabilities rather than prose:

| Primitive  | Question                        | Answer                                                   |
|------------|---------------------------------|----------------------------------------------------------|
| **noul**   | "Is this true?"                 | a calibrated probability in `[0, 1]`                      |
| **choice** | "Which one of these?"           | the selected option, the full distribution, a confidence  |
| **score**  | "Where on this scale?"          | a probability-weighted position, which can fall between levels |

One request carries one state and any number of named questions, answered in a single round trip. It is built for the
decisions inside an application — routing, gating, ranking, guardrails — not for writing paragraphs.

## Hello, Jev

```scala
import io.github.ticofab.jev._
import scala.concurrent.ExecutionContext.Implicits.global

val isUrgent = Question.noul("is_urgent", "Does this convey urgency?")

for {
  client   <- JevClient.futureFromEnv                // Either -- reads TYPESAFE_API_KEY
} yield client.askEither("Help! My payouts have been failing for 3 days.", isUrgent).map {
  case Right(response) => println(response.answers(isUrgent).probability) // 0.9200
  case Left(error)     => println(error.getMessage)
}
```

`answers(isUrgent)` returns a `NoulAnswer`, not an `Any` out of a `Map`. That is the one idea the whole API is built
on: **you look an answer up with the question value itself, and the type follows**.

## Asking several questions at once

```scala
val isUrgent    = Question.noul("is_urgent", "Does this convey urgency?")
val department  = Question.choice(
  "department",
  "Which team should handle this?",
  "billing"   -> "Payments, invoicing, refunds",
  "technical" -> "Bugs, outages, integrations",
  "sales"     -> "Pricing, upgrades, new accounts"
)
val frustration = Question.score(
  "frustration",
  "How frustrated is the customer?",
  "Calm and neutral", "Concerned but civil", "Very angry"
)

val response = client.askEither(message, isUrgent, department, frustration)

response.map { case Right(r) =>
  r.answers(isUrgent).probability            // 0.9200
  r.answers(department).choice               // "technical"
  r.answers(department).confidence           // 0.8200
  r.answers(frustration).score               // 1.6
  r.answers(frustration).nearestLabel        // "Very angry"
  r.usage.inputTokens                        // 312
}
```

### Choices that come back as your own type

Pass your own values and they come back unchanged, so a routing decision lands as a `Team` and not as a string you
have to re-parse and re-validate:

```scala
sealed trait Team
object Team {
  case object Billing   extends Team
  case object Technical extends Team
  case object Sales     extends Team
}

val department = Question.choiceOf[Team](
  "department",
  "Which team should handle this?",
  ChoiceOption(Team.Billing,   "billing",   "Payments, invoicing, refunds"),
  ChoiceOption(Team.Technical, "technical", "Bugs, outages, integrations"),
  ChoiceOption(Team.Sales,     "sales",     "Pricing, upgrades, new accounts")
)

val team: Team = response.answers(department).choice
```

### Probabilities are a type

Every probability Jev returns — a noul's answer, a distribution entry, a confidence — is a `Probability`, which cannot
hold a value outside `[0.0, 1.0]`. A response that tries to is a decoding error, not a number that quietly poisons an
average downstream.

```scala
val urgent = response.answers(isUrgent)
urgent.probability.value    // 0.92
urgent.probability.percent  // 92.0
urgent.isYes()              // true, at the default 0.5 threshold
urgent.isYes(0.95)          // false, at yours
```

There is deliberately no `Boolean` on the answer itself: `0.51` and `0.99` are very different answers, and collapsing
them at the SDK boundary throws away the part worth having.

### Reading a distribution

```scala
val team = response.answers(department)

team.ranked                      // Seq(Technical -> 0.8500, Billing -> 0.0800, Sales -> 0.0700)
team.probabilityOf(Team.Billing) // 0.0800
team.choiceIfConfident(0.9)      // None -- too close to call, escalate instead of routing
```

`choiceIfConfident` is the confidence-gated routing pattern in one call: act automatically when the distribution is
peaked, and fall through to a human (or to a slower model) when it is not.

### Structured state and instructions

Jev is trained on structure, so state, instructions, option descriptions and score levels all accept JSON as well as
text. `Content` is the type for all of them, and a `String` converts to it implicitly:

```scala
val consistent = Question.noul(
  "consistent_sender",
  Content.obj(
    "question" -> ujson.Str("Does the claimed identity conflict with the sending domain?"),
    "checking" -> ujson.Arr("from", "display_name")
  )
)

client.ask(
  Content.obj(
    "from"         -> ujson.Str("billing@acme-support.test"),
    "display_name" -> ujson.Str("Acme Billing")
  ),
  consistent
)
```

## Choosing an HTTP backend

The client is `JevClient[F]` for whatever `F` your sttp backend uses. Nothing else in the SDK changes.

**`Future`** — the default, no extra dependency:

```scala
import scala.concurrent.ExecutionContext.Implicits.global

val client: JevClient[Future] = JevClient.future(JevConfig("sk-..."))
```

**Blocking** — `Identity[A]` *is* `A`, so calls return the response directly. Good for scripts, tests and batch jobs:

```scala
val client: JevClient[Identity] = JevClient.sync(JevConfig("sk-..."))
val response: JevResponse       = client.ask(message, isUrgent)
```

**Anything else** — hand `JevClient` the backend, and a one-line `Sleeper` so retries can wait in your effect:

```scala
// build.sbt: "com.softwaremill.sttp.client4" %% "cats" % "4.0.26"
import cats.effect.IO
import sttp.client4.httpclient.cats.HttpClientCatsBackend

implicit val sleeper: Sleeper[IO] = Sleeper.fromFunction[IO](IO.sleep)

HttpClientCatsBackend.resource[IO]().use { backend =>
  val client = JevClient(backend, JevConfig("sk-..."))
  client.ask(message, isUrgent).flatMap(r => IO.println(r.answers(isUrgent).probability))
}
```

The same shape works for ZIO (`sttp.client4.impl.zio.HttpClientZioBackend`), Monix, Pekko, OkHttp, Armeria — every
sttp backend. `Sleeper` is the only thing the SDK cannot derive for an unknown effect, and it is only consulted when
retries are switched on; `JevConfig.withoutRetry` removes the need for one entirely.

## Configuration

```scala
val config = JevConfig("sk-...")
  .withModel("jev-1.13.0")            // default: jev-latest
  .withTimeout(10.seconds)            // default: 30s
  .withRetry(RetryPolicy(maxRetries = 5))
  .withHeader("X-Tenant", "acme")
  .withBaseUrl(sttp.model.Uri.unsafeParse("https://jev.internal.test/proxy"))
```

Or from the environment, which is what `JevConfig.fromEnv` and the `*FromEnv` constructors read:

| Variable                 | Meaning                              | Default                    |
|--------------------------|--------------------------------------|----------------------------|
| `TYPESAFE_API_KEY`       | required                             | —                          |
| `TYPESAFE_BASE_URL`      | API root                             | `https://api.typesafe.ai`  |
| `TYPESAFE_DEFAULT_MODEL` | model used when a call names none    | `jev-latest`               |

The API key is never printed: `JevConfig.toString` masks it.

A single call can override the model without a new client: `client.run(JevRequest.of(state, q).withModel("jev-1.13.0"))`.

## Failures

Everything fails as a `JevError`, a `RuntimeException` with a case for each way a call can go wrong:

```scala
client.askEither(message, isUrgent).map {
  case Right(response)                  => route(response)
  case Left(e: JevError.Validation)     => log.error(s"bad request: ${e.detail.getOrElse(e.body)}") // 422
  case Left(e: JevError.Authentication) => log.error("check TYPESAFE_API_KEY")                      // 401
  case Left(e: JevError.RateLimited)    => log.warn(s"still throttled; server said ${e.retryAfter}")// 429
  case Left(e) if e.isRetryable         => fallbackToSlowPath(e)          // 529, 5xx, connection
  case Left(e)                          => log.error(e.getMessage)
}
```

Every method comes in two flavours: `ask` / `run` / `listModels` raise the error in `F`, and `askEither` /
`runEither` / `listModelsEither` hand it back as a `Left`. Use whichever suits the surrounding code — a failed
`Future` and an `IO.raiseError` are both perfectly good, and so is an `Either`.

### Nothing throws

Building a question, an option, a request, a config or a retry policy is total: there is no input that makes a
constructor blow up. Everything that can be wrong is reported as a value instead.

Requests are checked locally before they are sent, and a bad one comes back as a `Left` without a round trip --
listing *every* problem, not just the first:

```scala
val answers = client.askEither(
  message,
  Question.noul("", "Is it?"),                        // no name
  Question.score("mood", "How?", "Only one"),         // needs >= 2 levels
  Question.choice("team", "Which?")                   // needs >= 1 option
)

// Left(InvalidRequest(List(
//   "question name is empty",
//   "score question 'mood' needs at least 2 levels, got 1",
//   "choice question 'team' needs at least one option"
// )))
```

`Probability` keeps its range invariant the same way: the constructor is private, and you reach it through
`Probability.clamp` (total) or `Probability.from` (`Either`). A retry policy with a negative retry count or a jitter
of `5.0` is clamped into range rather than rejected.

Two deliberate exceptions, both of which are the caller asking to be thrown at:

- `ask` / `run` / `listModels` raise the failure in `F` -- a failed `Future`, an `IO.raiseError`. That is how those
  effects signal errors, and for the blocking `Identity` backend it does mean a thrown exception. `askEither`,
  `runEither` and `listModelsEither` are the total counterparts.
- `answers(question)` throws if you hand it a question that was not in the request, the way `Map.apply` does.
  Decoding guarantees every question you *did* send has an answer, so this only fires on a genuine mix-up. Use
  `answers.get(question)` for an `Option`.

### Retries

On by default: three attempts after the first, exponential backoff from 500 ms capped at 8 s with 20% jitter, applied
only to failures where retrying can help — 429, 529, 5xx and connection errors. A 422 is never retried, because the
same invalid request will fail the same way. A `Retry-After` header wins over the computed backoff.

```scala
JevConfig("sk-...").withRetry(RetryPolicy(maxRetries = 5, initialDelay = 250.millis, maxDelay = 10.seconds))
JevConfig("sk-...").withoutRetry
```

## Escape hatches

Nothing here locks you into the typed surface:

- `JevRequest` can be built from a dynamically assembled `Seq[Question]`.
- `response.answers.raw("name")` reads an answer by string when the question was not a `val`.
- `Content.json(...)` takes an arbitrary `ujson.Value` and `Content.parse(...)` returns `Either[String, Content]`, so
  any shape the API accepts is reachable.
- `client.listModels` lists the models available to the account.

## Examples

Runnable, in [`examples/`](examples/src/main/scala/examples):

```bash
export TYPESAFE_API_KEY=sk-...
sbt "examples/runMain examples.FutureExample"        # Future, the default
sbt "examples/runMain examples.SyncExample"          # blocking, no wrapper
sbt "examples/runMain examples.CatsEffectExample"    # a pluggable backend
sbt "examples/runMain examples.ErrorHandlingExample" # failures and retry tuning
```

## Development

```bash
sbt +test              # both Scala versions
sbt scalafmtAll        # format
sbt +publishLocal      # install locally
```

Releases are cut by pushing a `v*` tag; see [PUBLISHING.md](PUBLISHING.md).

## Prior art and thanks

The typed-questions-in, typed-answers-out shape is borrowed from James Ward's
[zio-typesafe-ai](https://github.com/jamesward/zio-typesafe-ai), which does the same job for a ZIO codebase and uses
Scala 3 `NamedTuple`s to key answers by name. This SDK trades that for effect-agnosticism and Scala 2.13 support,
keying answers by the question values instead.

## License

[Apache 2.0](LICENSE).
