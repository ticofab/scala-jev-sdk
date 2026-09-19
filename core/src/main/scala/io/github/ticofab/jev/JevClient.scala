package io.github.ticofab.jev

import io.github.ticofab.jev.internal.Codec
import sttp.client4._
import sttp.model.{Header, MediaType, StatusCode}
import sttp.monad.MonadError
import sttp.monad.syntax._

import scala.concurrent.duration._
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
 * val isUrgent = Question.noul("is_urgent", "Does this convey urgency?")
 *
 * JevClient.create(DefaultFutureBackend(), JevConfig("sk-...")).foreach { client =>
 *   client.ask("Help! My payouts have been failing for 3 days.", isUrgent).foreach {
 *     case Right(response) => println(response.answers.get(isUrgent).map(_.probability))
 *     case Left(error)     => println(error.getMessage)
 *   }
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
    val retry: RetryPolicy,
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
        repeating { () =>
          send(post(systemOneUri, body)).map(_.flatMap(raw => Codec.parseResponse(raw, valid.questions)))
        }
    }

  /** The models available to this account. */
  def listModels: F[Either[JevError, Seq[ModelCard]]] =
    repeating(() => send(get(modelsUri)).map(_.flatMap(Codec.parseModels)))

  private def post(uri: sttp.model.Uri, body: String): Request[String] =
    basicRequest
      .post(uri)
      .body(body)
      .contentType(MediaType.ApplicationJson)
      .headers(baseHeaders*)
      .readTimeout(config.timeout)
      .response(asStringAlways)

  private def get(uri: sttp.model.Uri): Request[String] =
    basicRequest
      .get(uri)
      .headers(baseHeaders*)
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
  private def repeating[A](attempt: () => F[Either[JevError, A]]): F[Either[JevError, A]] = {
    def loop(attemptNumber: Int): F[Either[JevError, A]] =
      attempt().flatMap {
        case right @ Right(_)   => monad.unit(right)
        case left @ Left(error) =>
          if (attemptNumber > retry.retries || !retry.retryOn(error)) monad.unit(left)
          else
            sleeper
              .sleep(retry.delayFor(attemptNumber, JevClient.retryAfterOf(error)))
              .flatMap(_ => loop(attemptNumber + 1))
      }

    loop(1)
  }
}

object JevClient {

  private[jev] val UserAgent: String = "scala-jev-sdk"

  /**
   * A client over your sttp backend.
   *
   * The backend is yours: you choose the effect, the timeouts and the connection pool, and you close it when you are
   * done. `DefaultFutureBackend()` and `DefaultSyncBackend()` come with sttp core if you have no preference.
   *
   * Transient failures are retried by default -- 429, 529, 5xx and connection errors. Pass [[RetryPolicy.none]] for
   * exactly one attempt per call, or your own policy to tune the backoff.
   *
   * The [[Sleeper]] is how this client waits between attempts. `Future` and the blocking `Identity` have instances in
   * scope already; for cats-effect, ZIO or Monix it is a one-liner (see [[Sleeper.fromFunction]]).
   *
   * {{{
   * val client = JevClient.create(DefaultFutureBackend(), JevConfig("sk-..."))
   * }}}
   */
  def create[F[_]](backend: Backend[F], config: JevConfig, retry: RetryPolicy = RetryPolicy.default)(implicit
      sleeper: Sleeper[F]
  ): Either[JevError, JevClient[F]] =
    config.validate.map(valid => new JevClient(backend, valid, retry, sleeper))

  /**
   * A client over your sttp backend, configured from the environment.
   *
   * Reads `TYPESAFE_API_KEY` (required) plus `TYPESAFE_BASE_URL` and `TYPESAFE_DEFAULT_MODEL` when set. For a custom
   * policy alongside an environment config, go through [[JevConfig.fromEnv]]:
   * `JevConfig.fromEnv.flatMap(JevClient.create(backend, _, policy))`.
   */
  def create[F[_]](backend: Backend[F])(implicit sleeper: Sleeper[F]): Either[JevError, JevClient[F]] =
    JevConfig.fromEnv.flatMap(create(backend, _))

  private def retryAfterOf(response: Response[?]): Option[FiniteDuration] =
    response.header("Retry-After").flatMap(parseRetryAfter)

  private def retryAfterOf(error: JevError): Option[FiniteDuration] = error match {
    case JevError.RateLimited(retryAfter, _) => retryAfter
    case JevError.Overloaded(retryAfter, _)  => retryAfter
    case _                                   => None
  }

  private def parseRetryAfter(value: String): Option[FiniteDuration] =
    Try(value.trim.toLong).toOption.filter(_ >= 0).map(_.seconds)
}
