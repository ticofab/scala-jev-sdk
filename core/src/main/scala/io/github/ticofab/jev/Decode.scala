package io.github.ticofab.jev

import scala.collection.immutable.ListMap

/** Reading the pieces of a Jev response, reporting what went wrong instead of throwing. */
private[jev] object Decode {

  private val Tolerance = 1e-6

  def field(json: ujson.Value, name: String): Either[String, ujson.Value] =
    json.objOpt.flatMap(_.get(name)).toRight(s"missing field '$name'")

  def string(json: ujson.Value, name: String): Either[String, String] =
    field(json, name).flatMap(_.strOpt.toRight(s"field '$name' is not a string"))

  def double(json: ujson.Value, name: String): Either[String, Double] =
    field(json, name).flatMap(_.numOpt.toRight(s"field '$name' is not a number"))

  def int(json: ujson.Value, name: String): Either[String, Int] =
    double(json, name).map(_.toInt)

  def probability(json: ujson.Value, name: String): Either[String, Probability] =
    double(json, name).flatMap(d => toProbability(d, name))

  /**
   * Probabilities come off the wire as floats; absorb rounding at the bounds, reject anything genuinely out of range.
   */
  def toProbability(value: Double, where: String): Either[String, Probability] =
    if (value.isNaN) Left(s"'$where' is not a number")
    else if (value >= -Tolerance && value <= 1.0 + Tolerance) Right(Probability.clamp(value))
    else Left(s"'$where' is $value, outside [0.0, 1.0]")

  /** A `{ key: probability }` object, in the order the API returned it. */
  def distribution(json: ujson.Value, name: String): Either[String, ListMap[String, Probability]] =
    field(json, name).flatMap { raw =>
      raw.objOpt.toRight(s"field '$name' is not an object").flatMap { entries =>
        entries.foldLeft[Either[String, ListMap[String, Probability]]](Right(ListMap.empty)) {
          case (acc, (key, value)) =>
            for {
              soFar <- acc
              num   <- value.numOpt.toRight(s"'$name.$key' is not a number")
              prob  <- toProbability(num, s"$name.$key")
            } yield soFar.updated(key, prob)
        }
      }
    }

  /** Re-keys a choice distribution by the option values, in the order the question declared them. */
  def choiceDistribution[A](
      probabilities: ListMap[String, Probability],
      options: Seq[ChoiceOption[A]]
  ): Either[String, ListMap[A, Probability]] = {
    val known   = options.map(_.key).toSet
    val unknown = probabilities.keySet.diff(known)
    if (unknown.nonEmpty)
      Left(s"probabilities mention unknown option(s): ${unknown.toSeq.sorted.mkString(", ")}")
    else
      Right(ListMap(options.map(o => o.value -> probabilities.getOrElse(o.key, Probability.Zero))*))
  }

  /** Re-keys a score distribution by level index, lowest level first. */
  def intKeys(
      probabilities: ListMap[String, Probability],
      name: String = "probabilities"
  ): Either[String, ListMap[Int, Probability]] = {
    val parsed = probabilities.toSeq.map { case (key, prob) =>
      scala.util.Try(key.trim.toInt).toOption.map(_ -> prob).toRight(s"'$name' has non-integer level key '$key'")
    }
    sequence(parsed).map(pairs => ListMap(pairs.sortBy(_._1)*))
  }

  /**
   * The legend from the response, falling back to the levels the question was built with -- the legend is an echo of
   * the request, so a response that omits it is not a reason to fail.
   */
  def levelLegend(json: ujson.Value, levels: Seq[Content]): Either[String, ListMap[Int, Content]] = {
    val fallback = ListMap(levels.zipWithIndex.map { case (content, index) => index -> content }*)
    json.objOpt.flatMap(_.get("legend")) match {
      case None      => Right(fallback)
      case Some(raw) =>
        raw.objOpt.toRight("field 'legend' is not an object").flatMap { entries =>
          val parsed = entries.toSeq.map { case (key, value) =>
            scala.util
              .Try(key.trim.toInt)
              .toOption
              .map(_ -> Content(value))
              .toRight(s"'legend' has non-integer level key '$key'")
          }
          sequence(parsed).map(pairs => ListMap(pairs.sortBy(_._1)*))
        }
    }
  }

  def sequence[A](values: Seq[Either[String, A]]): Either[String, Seq[A]] =
    values.foldRight[Either[String, List[A]]](Right(Nil)) { (next, acc) =>
      for {
        tail <- acc
        head <- next
      } yield head :: tail
    }
}
