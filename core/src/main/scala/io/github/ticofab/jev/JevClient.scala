package io.github.ticofab.jev

import sttp.client4._
import sttp.model.{Header, MediaType, StatusCode}
import sttp.monad.MonadError
import sttp.monad.syntax._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * A client for Jev, TypeSafe AI's System One model.
 *
 * The client is parameterised by the effect `F` of the sttp backend you hand it, and nothing else. There is no effect
 * type of its own to learn and no effect system to adopt: give it `DefaultFutureBackend()` and every call returns a
 * `Future`; give it a cats-effect, ZIO, Monix or synchronous backend and every call returns that instead.
 *
 * {{{
 * import io.github.ticofab.jev._
 * import sttp.client4.DefaultFutureBackend
 * import scala.concurrent.ExecutionContext.Implicits.global
 *
 * val client = JevClient.future(JevConfig("sk-..."))
 *
 * val isUrgent = Question.noul("is_urgent", "Does this convey urgency?")
 *
 * client.ask("Help! My payouts have been failing for 3 days.", isUrgent).map {
 *   case Right(response) => println(response.answers.get(isUrgent).map(_.probability))
 *   case Left(error)     => println(error.getMessage)
 * }
 * }}}
 *
 * Nothing here throws. Every call returns `F[Either[JevError, ?]]`, so a rejected request, a transport failure and a
 * response this SDK cannot read all arrive the same way -- as a value. Retrying is on by default for transient
 * failures; see [[RetryPolicy]].
 */
final class JevClient[F[_]] private (
    backend: Backend[F],
    val config: JevConfig,
    sleeper: Sleeper[F]
) {

  implicit private val monad: MonadError[F] = backend.monad

  private val systemOneUri = config.baseUrl.addPath("v1", "systemone")
  private val modelsUri    = config.baseUrl.addPath("v1", "models")

  /** Asks `questions` about `state`. */
  def ask(state: Content, questions: Question*): F[Either[JevError, JevResponse]] =
    run(JevRequest(state, questions))

  /**
   * Sends a prepared request.
   *
   * The request is checked locally first: a malformed one comes back as [[JevError.InvalidRequest]] without a round
   * trip, listing everything that is wrong with it.
   */
  def run(request: JevRequest): F[Either[JevError, JevResponse]] =
    request.validate match {
      case Left(invalid) => monad.unit(Left(invalid))
      case Right(valid)  =>
        val body = valid.toJson(config.model).render()
        withRetries { () =>
          send(post(systemOneUri, body)).map(_.flatMap(raw => Codec.parseResponse(raw, valid.questions)))
        }
    }

  /** The models available to this account. */
  def listModels: F[Either[JevError, Seq[ModelCard]]] =
    withRetries(() => send(get(modelsUri)).map(_.flatMap(Codec.parseModels)))

  /** A copy of this client using `model` by default. */
  def withModel(model: String): JevClient[F] = withConfig(config.withModel(model))

  /** A copy of this client with a different retry policy. */
  def withRetry(policy: RetryPolicy): JevClient[F] = withConfig(config.withRetry(policy))

  /** A copy of this client with a different configuration, reusing the same backend. */
  def withConfig(updated: JevConfig): JevClient[F] = new JevClient(backend, updated, sleeper)

  /** Closes the underlying backend. */
  def close(): F[Unit] = backend.close()

  private def post(uri: sttp.model.Uri, body: String): Request[String] =
    basicRequest
      .post(uri)
      .body(body)
      .contentType(MediaType.ApplicationJson)
      .headers(baseHeaders: _*)
      .readTimeout(config.timeout)
      .response(asStringAlways)

  private def get(uri: sttp.model.Uri): Request[String] =
    basicRequest
      .get(uri)
      .headers(baseHeaders: _*)
      .readTimeout(config.timeout)
      .response(asStringAlways)

  private def baseHeaders: Seq[Header] =
    Seq(
      Header.authorization("Bearer", config.apiKey),
      Header.accept(MediaType.ApplicationJson),
      Header.userAgent(JevClient.UserAgent)
    ) ++ config.extraHeaders

  /** Runs one attempt, mapping transport failures and non-2xx statuses onto [[JevError]]. */
  private def send(request: Request[String]): F[Either[JevError, String]] =
    monad
      .handleError(request.send(backend).map(response => interpret(response))) { case failure: Throwable =>
        monad.unit(Left(JevError.Connection(failure)))
      }

  private def interpret(response: Response[String]): Either[JevError, String] = {
    val body       = response.body
    val retryAfter = JevClient.retryAfterOf(response)
    response.code match {
      case code if code.isSuccess         => Right(body)
      case StatusCode.Unauthorized        => Left(JevError.Authentication(body))
      case StatusCode.Forbidden           => Left(JevError.PermissionDenied(body))
      case StatusCode.NotFound            => Left(JevError.NotFound(body))
      case StatusCode.BadRequest          => Left(JevError.BadRequest(body))
      case StatusCode.UnprocessableEntity => Left(JevError.Validation(body))
      case StatusCode.TooManyRequests     => Left(JevError.RateLimited(retryAfter, body))
      case code if code.code == 529       => Left(JevError.Overloaded(retryAfter, body))
      case code if code.isServerError     => Left(JevError.ServerError(code.code, body))
      case code                           => Left(JevError.UnexpectedStatus(code.code, body))
    }
  }

  /** Repeats `attempt` while the policy says the failure is worth another try. */
  private def withRetries[A](attempt: () => F[Either[JevError, A]]): F[Either[JevError, A]] = {
    def loop(retry: Int): F[Either[JevError, A]] =
      attempt().flatMap {
        case right @ Right(_)   => monad.unit(right)
        case left @ Left(error) =>
          if (retry > config.retry.retries || !config.retry.retryOn(error)) monad.unit(left)
          else sleeper.sleep(config.retry.delayFor(retry, JevClient.retryAfterOf(error))).flatMap(_ => loop(retry + 1))
      }

    loop(1)
  }
}

object JevClient {

  private[jev] val UserAgent: String = "scala-jev-sdk"

  /** A client over any sttp backend. A [[Sleeper]] is only consulted when retries are enabled. */
  def apply[F[_]](backend: Backend[F], config: JevConfig)(implicit sleeper: Sleeper[F]): JevClient[F] =
    new JevClient(backend, config, sleeper)

  /** A client over any sttp backend, configured with just an API key. */
  def apply[F[_]](backend: Backend[F], apiKey: String)(implicit sleeper: Sleeper[F]): JevClient[F] =
    new JevClient(backend, JevConfig(apiKey), sleeper)

  /** A client over any sttp backend, reading `TYPESAFE_API_KEY` and friends from the environment. */
  def fromEnv[F[_]](backend: Backend[F])(implicit sleeper: Sleeper[F]): Either[JevError, JevClient[F]] =
    JevConfig.fromEnv.map(config => new JevClient(backend, config, sleeper))

  /**
   * The batteries-included client: sttp's default `Future` backend over the JDK HTTP client, no extra dependency.
   *
   * Call `close()` when you are done with it.
   */
  def future(config: JevConfig)(implicit ec: ExecutionContext): JevClient[Future] =
    apply(DefaultFutureBackend(), config)(Sleeper.future)

  /** A `Future` client reading its configuration from the environment. */
  def futureFromEnv(implicit ec: ExecutionContext): Either[JevError, JevClient[Future]] =
    JevConfig.fromEnv.map(future)

  /**
   * A blocking client: every call returns its result directly, with no wrapper at all.
   *
   * `Identity[A]` is `A`, so `client.ask(...)` returns a [[JevResponse]]. Handy in scripts, tests and Spark jobs.
   */
  def sync(config: JevConfig): JevClient[sttp.shared.Identity] =
    apply(DefaultSyncBackend(), config)

  /** A blocking client reading its configuration from the environment. */
  def syncFromEnv: Either[JevError, JevClient[sttp.shared.Identity]] =
    JevConfig.fromEnv.map(sync)

  private def retryAfterOf(response: Response[_]): Option[FiniteDuration] =
    response.header("Retry-After").flatMap(parseRetryAfter)

  private def retryAfterOf(error: JevError): Option[FiniteDuration] = error match {
    case JevError.RateLimited(retryAfter, _) => retryAfter
    case JevError.Overloaded(retryAfter, _)  => retryAfter
    case _                                   => None
  }

  private def parseRetryAfter(value: String): Option[FiniteDuration] =
    Try(value.trim.toLong).toOption.filter(_ >= 0).map(_.seconds)
}
