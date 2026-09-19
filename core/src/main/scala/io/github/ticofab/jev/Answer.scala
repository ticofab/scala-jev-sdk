package io.github.ticofab.jev

import scala.collection.immutable.ListMap

/** An answer to a single [[Question]]. */
sealed abstract class Answer {

  /** The wire discriminator: `"noul"`, `"choice"` or `"score"`. */
  def questionType: String

  /** How peaked the underlying distribution is, in `[0.0, 1.0]`. */
  def confidence: Probability
}

/**
 * The answer to a [[Question.Noul]]: a calibrated probability that the answer is yes.
 *
 * There is deliberately no `Boolean` here. `0.51` and `0.99` are very different answers, and collapsing them at the SDK
 * boundary throws away the part of the answer that is worth having. Call [[isYes]] when you do want the collapse, and
 * pass your own threshold.
 */
final case class NoulAnswer(probability: Probability) extends Answer {

  val questionType: String = "noul"

  /** True when the probability is at or above `threshold`. */
  def isYes(threshold: Double = 0.5): Boolean = probability.value >= threshold

  /** True when the probability is below `threshold`. */
  def isNo(threshold: Double = 0.5): Boolean = !isYes(threshold)

  /**
   * How far the answer sits from a coin flip, rescaled to `[0.0, 1.0]`.
   *
   * Derived locally -- Jev does not return a separate confidence for nouls, because for a two-outcome distribution the
   * probability already carries it.
   */
  def confidence: Probability = Probability.clamp(math.abs(probability.value - 0.5) * 2.0)
}

/**
 * The answer to a [[Question.Choice]]: the selected option plus the full distribution over all options.
 *
 * `A` is the option type of the question that produced this answer, so `choice` is your own value -- an enum case, a
 * handler, a string -- and not a key you have to look up.
 */
final case class ChoiceAnswer[A](
    choice: A,
    key: String,
    probabilities: ListMap[A, Probability],
    confidence: Probability
) extends Answer {

  val questionType: String = "choice"

  /** The probability assigned to `option`, or zero if Jev did not mention it. */
  def probabilityOf(option: A): Probability = probabilities.getOrElse(option, Probability.Zero)

  /** The probability of the selected option. */
  def probability: Probability = probabilityOf(choice)

  /** Options ordered from most to least likely. */
  def ranked: Seq[(A, Probability)] = probabilities.toSeq.sortBy(-_._2.value)

  /** The selected option, but only when the distribution is peaked enough to act on unsupervised. */
  def choiceIfConfident(threshold: Double = 0.8): Option[A] =
    if (confidence.value >= threshold) Some(choice) else None
}

/**
 * The answer to a [[Question.Score]]: a probability-weighted position on the question's scale.
 *
 * `score` is continuous and can fall between levels; `probabilities` and `legend` are keyed by level index, counting
 * from zero.
 */
final case class ScoreAnswer(
    score: Double,
    legend: ListMap[Int, Content],
    probabilities: ListMap[Int, Probability],
    confidence: Probability
) extends Answer {

  val questionType: String = "score"

  /** The highest level index on this scale. */
  def maxLevel: Int = if (legend.isEmpty) 0 else legend.keys.max

  /** The score rescaled to `[0.0, 1.0]`, for combining scales of different lengths. */
  def normalized: Double = if (maxLevel <= 0) 0.0 else score / maxLevel.toDouble

  /** The level index the score rounds to. */
  def nearestLevel: Int = math.round(score).toInt

  /** The description of the level the score rounds to. */
  def nearestLabel: String = legend.get(nearestLevel).fold("")(_.asString)

  /** The single most likely level, which is not always [[nearestLevel]] on a skewed distribution. */
  def mostLikelyLevel: Int = if (probabilities.isEmpty) nearestLevel else probabilities.maxBy(_._2.value)._1

  /** The probability assigned to `level`, or zero. */
  def probabilityOf(level: Int): Probability = probabilities.getOrElse(level, Probability.Zero)
}

/**
 * The answers to one request, keyed by the questions that produced them.
 *
 * Look an answer up with the question value itself and the result is already the right type:
 *
 * {{{
 * for {
 *   response <- client.ask(state, isUrgent, department) // Either[JevError, JevResponse]
 *   urgent   <- response.answers.get(isUrgent).toRight(missing)
 * } yield urgent.probability // a Probability, not an Any out of a Map
 * }}}
 */
final class Answers private[jev] (private val byName: Map[String, Answer]) {

  /**
   * The answer to `question`, if this response holds one.
   *
   * In practice it always does for a question the request carried: decoding rejects a response that is missing any of
   * them, so a [[JevResponse]] only ever exists with a complete set. The `Option` is `None` when you pass a question
   * belonging to a different request.
   */
  def get(question: Question): Option[question.Out] =
    byName.get(question.name).map(_.asInstanceOf[question.Out])

  /** The answer keyed by `name`, untyped -- an escape hatch for dynamically built questions. */
  def raw(name: String): Option[Answer] = byName.get(name)

  /** Every answer, keyed by question name. */
  def toMap: Map[String, Answer] = byName

  /** The question names present in the response. */
  def names: Set[String] = byName.keySet

  def size: Int = byName.size

  override def toString: String = byName.map { case (k, v) => s"$k -> $v" }.mkString("Answers(", ", ", ")")
}

/** Token counts for one request. Jev bills on input tokens only. */
final case class Usage(inputTokens: Int, outputTokens: Int) {
  def totalTokens: Int = inputTokens + outputTokens
}

/** A complete response: the model that answered, the answers, and the token usage. */
final case class JevResponse(model: String, answers: Answers, usage: Usage)

/** One model available to the account, as returned by `GET /v1/models`. */
final case class ModelCard(name: String, description: String, releaseDate: String)
