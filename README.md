# scala-jev-sdk

A Scala client for [Jev](https://docs.typesafe.ai), TypeSafe AI's System One model — the model that answers typed
questions about a piece of state instead of generating text.

No effect system. You hand it an [sttp](https://sttp.softwaremill.com) backend and the client speaks whatever that
backend speaks — `Future`, blocking `Identity` with no wrapper at all, or cats-effect, ZIO, Monix and Pekko if that
is what your codebase already runs on.

[![CI](https://github.com/ticofab/scala-jev-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/ticofab/scala-jev-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ticofab/scala-jev-sdk_3.svg)](https://central.sonatype.com/artifact/io.github.ticofab/scala-jev-sdk_3)

```scala
libraryDependencies += "io.github.ticofab" %% "scala-jev-sdk" % "<version>"
```

Scala 3.3+ (LTS), JDK 17+. The only dependencies are `sttp-client4-core` and `upickle`.

Published against Scala 3.3 LTS on purpose: TASTy is forward-compatible but not backward, so an artifact built on a
newer Scala would shut out every project still on LTS. Built on 3.3 it is readable from 3.3 through 3.9 and beyond.

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
import sttp.client4.DefaultFutureBackend
import scala.concurrent.ExecutionContext.Implicits.global

val isUrgent = Question.noul("is_urgent", "Does this convey urgency?")

JevClient.create(DefaultFutureBackend()).foreach { client => // Either -- reads TYPESAFE_API_KEY
  client.ask("Help! My payouts have been failing for 3 days.", isUrgent).foreach {
    case Right(response) => println(response.answers.get(isUrgent).map(_.probability)) // Some(0.9200)
    case Left(error)     => println(error.getMessage)
  }
}
```

`answers.get(isUrgent)` returns an `Option[NoulAnswer]`, not an `Any` out of a `Map`. That is the one idea the whole
API is built on: **you look an answer up with the question value itself, and the type follows**.

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

client.ask(message, isUrgent, department, frustration).foreach {
  case Left(error) => println(error.getMessage)
  case Right(r) =>
    for {
      urgent <- r.answers.get(isUrgent)
      team   <- r.answers.get(department)
      mood   <- r.answers.get(frustration)
    } yield {
      urgent.probability  // 0.9200
      team.choice         // "technical"
      team.confidence     // 0.8200
      mood.score          // 1.6
      mood.nearestLabel   // "Very angry"
      r.usage.inputTokens // 312
    }
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

val team: Option[Team] = response.answers.get(department).map(_.choice)
```

### Probabilities are a type

Every probability Jev returns — a noul's answer, a distribution entry, a confidence — is a `Probability`, which cannot
hold a value outside `[0.0, 1.0]`. A response that tries to is a decoding error, not a number that quietly poisons an
average downstream.

```scala
response.answers.get(isUrgent).foreach { urgent =>
  urgent.probability.value    // 0.92
  urgent.probability.percent  // 92.0
  urgent.isYes()              // true, at the default 0.5 threshold
  urgent.isYes(0.95)          // false, at yours
}
```

There is deliberately no `Boolean` on the answer itself: `0.51` and `0.99` are very different answers, and collapsing
them at the SDK boundary throws away the part worth having.

### Reading a distribution

```scala
response.answers.get(department).foreach { team =>
  team.ranked                      // Seq(Technical -> 0.8500, Billing -> 0.0800, Sales -> 0.0700)
  team.probabilityOf(Team.Billing) // 0.0800
  team.choiceIfConfident(0.9)      // None — too close to call, escalate instead of routing
}
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

There is one constructor, and it always takes a backend:

```scala
JevClient.create(backend, config)           // Either[JevError, JevClient[F]]
JevClient.create(backend, config, policy)   // ... with your own retry policy
JevClient.create(backend)                   // ... config from the environment
```

Every form returns an `Either`, because building a client can genuinely fail: a blank API key, an empty model name
or a non-positive timeout is caught here rather than surfacing as a puzzling `401` on the first call. `F` is whatever
your backend uses, and nothing else in the SDK changes. The backend is yours: you pick the effect,
the timeouts and the connection pool, and you close it when you are done.

**`Future`** — `DefaultFutureBackend` ships with sttp core, so this needs no extra dependency:

```scala
import sttp.client4.DefaultFutureBackend
import scala.concurrent.ExecutionContext.Implicits.global

val client: Either[JevError, JevClient[Future]] = JevClient.create(DefaultFutureBackend(), JevConfig("sk-..."))
```

**Blocking** — `Identity[A]` *is* `A`, so calls return the `Either` directly with no wrapper. Good for scripts, tests
and batch jobs:

```scala
import sttp.client4.DefaultSyncBackend
import sttp.shared.Identity

val client: Either[JevError, JevClient[Identity]] = JevClient.create(DefaultSyncBackend(), JevConfig("sk-..."))
```

**cats-effect** — and the same shape for ZIO (`HttpClientZioBackend`), Monix, Pekko, OkHttp, Armeria:

```scala
// build.sbt: "com.softwaremill.sttp.client4" %% "cats" % "4.0.26"
import cats.effect.IO
import sttp.client4.httpclient.cats.HttpClientCatsBackend

HttpClientCatsBackend.resource[IO]().use { backend =>
  JevClient.create(backend, JevConfig("sk-...")) match {
    case Left(error)   => IO.println(error.getMessage)
    case Right(client) => client.ask(message, isUrgent).flatMap(r => IO.println(r.map(_.answers.get(isUrgent))))
  }
}
```

## Configuration

```scala
val config = JevConfig("sk-...")
  .withModel("jev-1.13.0")            // default: jev-latest
  .withTimeout(10.seconds)            // default: 30s
  .withHeader("X-Tenant", "acme")
```

`baseUrl` is an sttp `Uri`, and `Uri.parse` hands back an `Either` like everything else here:

```scala
import sttp.model.Uri

Uri.parse("https://jev.internal.test/proxy").map(config.withBaseUrl)
```

Or from the environment, which is what `JevConfig.fromEnv` and the one-argument `JevClient.create` read:

| Variable                 | Meaning                              | Default                    |
|--------------------------|--------------------------------------|----------------------------|
| `TYPESAFE_API_KEY`       | required                             | —                          |
| `TYPESAFE_BASE_URL`      | API root                             | `https://api.typesafe.ai`  |
| `TYPESAFE_DEFAULT_MODEL` | model used when a call names none    | `jev-latest`               |

The API key is never printed: `JevConfig.toString` masks it.

A client is a thin value over your backend, so there are no "derive a variant" methods on it — to change anything,
build another one with the same backend. For a single call, override the model on the request instead:

```scala
client.run(JevRequest.of(state, question).withModel("jev-1.13.0"))
```

## Failures

Everything fails as a `JevError`, a `RuntimeException` with a case for each way a call can go wrong:

```scala
client.ask(message, isUrgent).map {
  case Right(response)                  => route(response)
  case Left(e: JevError.Validation)     => log.error(s"bad request: ${e.detail.getOrElse(e.body)}") // 422
  case Left(e: JevError.Authentication) => log.error("check TYPESAFE_API_KEY")                      // 401
  case Left(e: JevError.RateLimited)    => log.warn(s"still throttled; server said ${e.retryAfter}")// 429
  case Left(e) if e.isRetryable         => fallbackToSlowPath(e)          // 529, 5xx, connection
  case Left(e)                          => log.error(e.getMessage)
}
```

There is one flavour of each call and it always returns `F[Either[JevError, ?]]`. A rejected request, a transport
failure and a response this SDK cannot read all arrive the same way, so there is no path where an error reaches you
as anything other than a value.

### Nothing throws

Building a question, an option, a request, a config or a retry policy is total: there is no input that makes a
constructor blow up. Everything that can be wrong is reported as a value instead.

Requests are checked locally before they are sent, and a bad one comes back as a `Left` without a round trip —
listing *every* problem, not just the first:

```scala
val answers = client.ask(
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

A configuration is checked the same way, when a client is built from it:

```scala
JevClient.create(backend, JevConfig("   "))
// Left(InvalidConfig(List("the API key is empty")))
```

`Probability` keeps its range invariant differently: the constructor is private, and you reach it through
`Probability.clamp` (total) or `Probability.from` (`Either`). A retry policy with a negative retry count or a jitter
of `5.0` is clamped into range rather than rejected.

There are no exceptions to this. Nothing in the library throws, no call raises into the effect's error channel, and
there is no `unsafe`-flavoured escape hatch. `answers.get(question)` returns an `Option` rather than throwing on a
question from a different request — for questions the request actually carried it is always present, since decoding
rejects a response that is missing any of them.

### Retries

Retrying is a constructor argument, on by default:

```scala
JevClient.create(backend, config)                                // three retries, exponential backoff
JevClient.create(backend, config, RetryPolicy(maxRetries = 5))   // your policy
JevClient.create(backend, config, RetryPolicy.none)              // exactly one attempt per call
```

Waiting between attempts is the one thing an arbitrary `F` cannot do on its own, so the client needs a `Sleeper`.
`Future` and the blocking `Identity` have instances in scope and you will never notice; for cats-effect, ZIO or
Monix it is a one-liner, needed even if you pass `RetryPolicy.none`:

```scala
implicit val sleeper: Sleeper[IO] = Sleeper.fromFunction[IO](IO.sleep)
```

For an environment config with a custom policy, go through `JevConfig.fromEnv`:

```scala
JevConfig.fromEnv.flatMap(JevClient.create(backend, _, policy))
```

The defaults are three attempts after the first, exponential backoff from 500 ms capped at 8 s with 20% jitter,
applied only to failures where retrying can help — 429, 529, 5xx and connection errors. A 422 is never retried,
because the same invalid request will fail the same way, and neither is a locally-rejected `InvalidRequest`. A
`Retry-After` header wins over the computed backoff.

**Or don't use ours at all.** sttp deliberately ships no retry support, on the grounds that resilience belongs to
your stack, and that reasoning applies here too: `JevError.isRetryable` and `JevError.RateLimited.retryAfter` give a
retry library everything it needs. Wrap `client.ask(...)` with [cats-retry](https://cb372.github.io/cats-retry/),
ZIO's `Schedule`, [Ox](https://ox.softwaremill.com) or [`retry`](https://github.com/softwaremill/retry) and use
`JevClient.create` on its own instead.

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
sbt "examples/runMain examples.FutureExample"        # Future over sttp's JDK backend
sbt "examples/runMain examples.SyncExample"          # blocking Identity, no wrapper
sbt "examples/runMain examples.CatsEffectExample"    # cats-effect IO, with its own timer
sbt "examples/runMain examples.ErrorHandlingExample" # failures and retry tuning
```

## Development

```bash
sbt test               # compile and test
sbt scalafmtAll        # format
sbt publishLocal       # install locally
```

Releases are cut by pushing a `v*` tag; see [PUBLISHING.md](PUBLISHING.md).

## A note on naming

`JevClient`, `JevConfig`, `JevError`, `JevRequest` and `JevResponse` carry a prefix; `Question`, `Answer`, `Content`,
`Probability` and `RetryPolicy` do not. That is deliberate rather than sloppy: the prefixed names are exactly the ones
that would collide on a wildcard import, since `Client`, `Config`, `Error`, `Request` and `Response` are everywhere.

Everything public lives in the single package `io.github.ticofab.jev`, so one import is enough. The
`io.github.ticofab.jev.internal` package holds the JSON codec and validation helpers; they are `private[jev]` and not
part of the compatibility promise.

## Prior art and thanks

The typed-questions-in, typed-answers-out shape is borrowed from James Ward's
[zio-typesafe-ai](https://github.com/jamesward/zio-typesafe-ai), which does the same job for a ZIO codebase and uses
Scala 3 `NamedTuple`s to key answers by name. This SDK trades that for effect-agnosticism, keying answers by the
question values instead.

## License

[Apache 2.0](LICENSE).
