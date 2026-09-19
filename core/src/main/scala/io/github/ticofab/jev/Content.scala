package io.github.ticofab.jev

import scala.language.implicitConversions

/**
 * A piece of content sent to Jev.
 *
 * Jev accepts a plain string, a JSON object or a JSON array wherever free-form content is expected: the request
 * `state`, a question's `instructions`, and the descriptions attached to choice options, score levels and noul
 * criteria. `Content` is the single type used for all of those.
 *
 * A `String` converts to `Content` implicitly, so the common case reads naturally:
 *
 * {{{
 * Question.noul("is_urgent", "Does this convey urgency?")
 * }}}
 *
 * For structured content, wrap a `ujson.Value`:
 *
 * {{{
 * Content.obj(
 *   "question" -> ujson.Str("Is the sender domain consistent with the claimed identity?"),
 *   "record"   -> ujson.Obj("from" -> "billing@acme.test", "claims" -> "Acme Support")
 * )
 * }}}
 */
final case class Content(json: ujson.Value) {

  /** The content rendered as a string: the raw text for a JSON string, compact JSON otherwise. */
  def asString: String = json match {
    case ujson.Str(s) => s
    case other        => other.render()
  }

  override def toString: String = asString
}

object Content {

  /** Content holding a plain string. */
  def text(value: String): Content = Content(ujson.Str(value))

  /** Content holding an arbitrary JSON value. */
  def json(value: ujson.Value): Content = Content(value)

  /** Content holding a JSON object. */
  def obj(fields: (String, ujson.Value)*): Content = Content(ujson.Obj.from(fields))

  /** Content holding a JSON array. */
  def arr(values: ujson.Value*): Content = Content(ujson.Arr.from(values))

  /** Content parsed from a JSON string, or a description of why it could not be parsed. */
  def parse(raw: String): Either[String, Content] =
    scala.util
      .Try(ujson.read(raw))
      .toEither
      .left
      .map(failure => s"not valid JSON: ${failure.getMessage}")
      .map(Content(_))

  implicit def fromString(value: String): Content = text(value)

  implicit def fromJson(value: ujson.Value): Content = Content(value)
}
