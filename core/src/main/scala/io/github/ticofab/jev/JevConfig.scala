package io.github.ticofab.jev

import sttp.model.{Header, Uri}

import scala.concurrent.duration._

/**
 * Everything the client needs besides an HTTP backend.
 *
 * {{{
 * val config = JevConfig("sk-...")
 *   .withModel("jev-1.13.0")
 *   .withTimeout(10.seconds)
 *   .withHeader("X-Tenant", "acme")
 * }}}
 *
 * A configuration is checked when a client is built from it, not here -- see [[JevConfig.validate]].
 *
 * @param apiKey
 *   sent as `Authorization: Bearer <apiKey>`
 * @param baseUrl
 *   the API root, without a trailing path
 * @param model
 *   the model used when a call does not name one
 * @param timeout
 *   per-request timeout
 * @param extraHeaders
 *   headers added to every request
 */
final case class JevConfig(
    apiKey: String,
    baseUrl: Uri = JevConfig.DefaultBaseUrl,
    model: String = JevConfig.DefaultModel,
    timeout: FiniteDuration = JevConfig.DefaultTimeout,
    extraHeaders: Seq[Header] = Nil
) {
  def withApiKey(value: String): JevConfig               = copy(apiKey = value)
  def withBaseUrl(value: Uri): JevConfig                 = copy(baseUrl = value)
  def withModel(value: String): JevConfig                = copy(model = value)
  def withTimeout(value: FiniteDuration): JevConfig      = copy(timeout = value)
  def withHeader(name: String, value: String): JevConfig =
    copy(extraHeaders = extraHeaders :+ Header(name, value))

  /**
   * Everything wrong with this configuration, empty when it is usable.
   *
   * Mirrors [[JevRequest.problems]]: all of them at once, rather than stopping at the first.
   */
  def problems: List[String] = {
    val blankKey   = if (apiKey == null || apiKey.trim.isEmpty) List("the API key is empty") else Nil
    val blankModel = if (model == null || model.trim.isEmpty) List("the model name is empty") else Nil
    val badTimeout = if (timeout <= Duration.Zero) List(s"the timeout must be positive, got $timeout") else Nil

    blankKey ::: blankModel ::: badTimeout
  }

  /** This configuration, if it is usable, or every reason it is not. */
  def validate: Either[JevError, JevConfig] =
    problems match {
      case Nil      => Right(this)
      case problems => Left(JevError.InvalidConfig(problems))
    }

  /** The API key is never printed. */
  override def toString: String =
    s"JevConfig(apiKey=***, baseUrl=$baseUrl, model=$model, timeout=$timeout)"
}

object JevConfig {

  val ApiKeyEnv: String       = "TYPESAFE_API_KEY"
  val BaseUrlEnv: String      = "TYPESAFE_BASE_URL"
  val DefaultModelEnv: String = "TYPESAFE_DEFAULT_MODEL"

  /** Built from parts rather than parsed, so even this constant needs no partial function. */
  val DefaultBaseUrl: Uri            = Uri(scheme = "https", host = "api.typesafe.ai")
  val DefaultModel: String           = "jev-latest"
  val DefaultTimeout: FiniteDuration = 30.seconds

  /**
   * Reads the configuration from the environment: `TYPESAFE_API_KEY` (required), plus `TYPESAFE_BASE_URL` and
   * `TYPESAFE_DEFAULT_MODEL` when set.
   */
  def fromEnv: Either[JevError, JevConfig] =
    sys.env.get(ApiKeyEnv).map(_.trim).filter(_.nonEmpty) match {
      case None      => Left(JevError.MissingApiKey(ApiKeyEnv))
      case Some(key) =>
        val model = sys.env.get(DefaultModelEnv).map(_.trim).filter(_.nonEmpty).getOrElse(DefaultModel)

        sys.env.get(BaseUrlEnv).map(_.trim).filter(_.nonEmpty) match {
          case None      => Right(JevConfig(apiKey = key, baseUrl = DefaultBaseUrl, model = model))
          case Some(raw) =>
            Uri
              .parse(raw)
              .left
              .map(reason => JevError.InvalidConfig(List(s"$BaseUrlEnv is not a valid URL ($reason): $raw")))
              .map(base => JevConfig(apiKey = key, baseUrl = base, model = model))
        }
    }
}
