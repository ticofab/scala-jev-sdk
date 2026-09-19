package io.github.ticofab.jev

import scala.language.implicitConversions

/**
 * A single typed question about the request's state.
 *
 * A question carries the `name` it will be keyed by in the request and in the response, so a question value is all you
 * need to read its answer back:
 *
 * {{{
 * val isUrgent = Question.noul("is_urgent", "Does this convey urgency?")
 * val answers  = client.ask(state, isUrgent)
 * answers(isUrgent).probability // a Probability, not a Double from a Map[String, Any]
 * }}}
 *
 * The `Out` type member is what makes that work: each question subtype fixes the answer type it produces, and
 * [[Answers.apply]] returns exactly that type.
 */
sealed abstract class Question {

  /** The answer type this question produces. */
  type Out <: Answer

  /** The key this question is sent and answered under. Must be unique within a request. */
  def name: String

  /** What Jev is being asked. */
  def instructions: Content

  /**
   * Everything wrong with this question, empty when it is valid.
   *
   * Questions are built with total constructors -- nothing throws -- and are checked when the request that carries them
   * is sent. See [[JevRequest.validate]].
   */
  private[jev] def problems: List[String]

  /** The wire value of this question, without its name. */
  private[jev] def toJson: ujson.Value

  private[jev] def decode(json: ujson.Value): Either[String, Out]
}

object Question {

  /**
   * A yes/no question. The answer is a calibrated probability rather than a boolean, so the caller decides where the
   * threshold sits -- or routes on the probability directly.
   */
  final case class Noul(
      name: String,
      instructions: Content,
      criteria: Option[NoulCriteria] = None
  ) extends Question {

    type Out = NoulAnswer

    private[jev] def problems: List[String] = Validate.name(name, "question name")

    private[jev] def toJson: ujson.Value = {
      val fields = ujson.Obj("type" -> ujson.Str("noul"), "instructions" -> instructions.json)
      criteria.foreach(c => fields("criteria") = c.toJson)
      fields
    }

    private[jev] def decode(json: ujson.Value): Either[String, NoulAnswer] =
      Decode.probability(json, "noul").map(NoulAnswer.apply)
  }

  /**
   * A "pick one" question over a fixed set of options.
   *
   * `A` is the type of the option values. Use [[Question.choice]] when the options are plain strings, and
   * [[Question.choiceOf]] to map the options onto your own type (an enum, a sealed trait, a set of handlers) so the
   * answer comes back as that type instead of a string you have to re-parse.
   */
  final case class Choice[A](
      name: String,
      instructions: Content,
      options: Seq[ChoiceOption[A]]
  ) extends Question {

    type Out = ChoiceAnswer[A]

    private[jev] def problems: List[String] = {
      val keys       = options.map(_.key)
      val duplicates = keys.diff(keys.distinct).distinct

      Validate.name(name, "question name") :::
        (if (options.isEmpty) List(s"choice question '$name' needs at least one option") else Nil) :::
        (if (options.sizeIs > 255) List(s"choice question '$name' has ${options.size} options, the maximum is 255")
         else Nil) :::
        (if (duplicates.nonEmpty)
           List(s"choice question '$name' has duplicate option keys: ${duplicates.mkString(", ")}")
         else Nil) :::
        options.toList.flatMap(o => Validate.name(o.key, s"option key of question '$name'"))
    }

    /** The option carrying `key`, if any. */
    def optionFor(key: String): Option[ChoiceOption[A]] = options.find(_.key == key)

    private[jev] def toJson: ujson.Value = {
      val criteria = ujson.Obj()
      options.foreach(o => criteria(o.key) = o.description.fold[ujson.Value](ujson.Null)(_.json))
      ujson.Obj("type" -> ujson.Str("choice"), "instructions" -> instructions.json, "criteria" -> criteria)
    }

    private[jev] def decode(json: ujson.Value): Either[String, ChoiceAnswer[A]] =
      for {
        key      <- Decode.string(json, "choice")
        selected <- optionFor(key).toRight(
          s"answer selected unknown option '$key'; expected one of ${options.map(_.key).mkString(", ")}"
        )
        probs   <- Decode.distribution(json, "probabilities")
        byValue <- Decode.choiceDistribution(probs, options)
        conf    <- Decode.probability(json, "confidence")
      } yield ChoiceAnswer(selected.value, key, byValue, conf)
  }

  /**
   * A question answered on an ordered scale of two or more levels.
   *
   * The answer is a probability-weighted position on that scale, so it can fall between levels: a `1.6` on
   * `["Calm", "Frustrated", "Very angry"]` sits between "Frustrated" and "Very angry".
   */
  final case class Score(
      name: String,
      instructions: Content,
      levels: Seq[Content]
  ) extends Question {

    type Out = ScoreAnswer

    private[jev] def problems: List[String] =
      Validate.name(name, "question name") :::
        (if (levels.sizeIs < 2) List(s"score question '$name' needs at least 2 levels, got ${levels.size}") else Nil)

    /** The highest level index, i.e. `levels.size - 1`. */
    def maxLevel: Int = levels.size - 1

    private[jev] def toJson: ujson.Value =
      ujson.Obj(
        "type"         -> ujson.Str("score"),
        "instructions" -> instructions.json,
        "criteria"     -> ujson.Arr.from(levels.map(_.json))
      )

    private[jev] def decode(json: ujson.Value): Either[String, ScoreAnswer] =
      for {
        score  <- Decode.double(json, "score")
        legend <- Decode.levelLegend(json, levels)
        probs  <- Decode.distribution(json, "probabilities")
        byLvl  <- Decode.intKeys(probs, "probabilities")
        conf   <- Decode.probability(json, "confidence")
      } yield ScoreAnswer(score, legend, byLvl, conf)
  }

  /** A yes/no question. */
  def noul(name: String, instructions: Content): Noul =
    Noul(name, instructions, None)

  /** A yes/no question that spells out what each answer means. */
  def noul(name: String, instructions: Content, ifTrue: Content, ifFalse: Content): Noul =
    Noul(name, instructions, Some(NoulCriteria(Some(ifTrue), Some(ifFalse))))

  /**
   * A "pick one" question over string options.
   *
   * Options are given as bare keys or as `key -> description` pairs, and may be mixed:
   *
   * {{{
   * Question.choice(
   *   "department",
   *   "Which team should handle this?",
   *   "billing"   -> "Payments, invoicing, refunds",
   *   "technical" -> "Bugs, outages, integrations",
   *   "sales"
   * )
   * }}}
   */
  def choice(name: String, instructions: Content, options: ChoiceOption[String]*): Choice[String] =
    Choice(name, instructions, options)

  /**
   * A "pick one" question whose answer comes back as a value of your own type.
   *
   * {{{
   * sealed trait Team
   * object Team { case object Billing extends Team; case object Technical extends Team }
   *
   * Question.choiceOf(
   *   "department",
   *   "Which team should handle this?",
   *   ChoiceOption(Team.Billing,   "billing",   "Payments, invoicing, refunds"),
   *   ChoiceOption(Team.Technical, "technical", "Bugs, outages, integrations")
   * )
   * }}}
   */
  def choiceOf[A](name: String, instructions: Content, options: ChoiceOption[A]*): Choice[A] =
    Choice(name, instructions, options)

  /**
   * A question on an ordered scale, lowest level first.
   *
   * {{{
   * Question.score("frustration", "How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
   * }}}
   */
  def score(name: String, instructions: Content, levels: Content*): Score =
    Score(name, instructions, levels)
}

/**
 * One option of a [[Question.Choice]]: the value handed back to you, the `key` sent over the wire, and an optional
 * description telling Jev what the option covers.
 */
final case class ChoiceOption[+A](value: A, key: String, description: Option[Content])

object ChoiceOption {

  def apply[A](value: A, key: String): ChoiceOption[A] = ChoiceOption(value, key, None)

  def apply[A](value: A, key: String, description: Content): ChoiceOption[A] =
    ChoiceOption(value, key, Some(description))

  /** `"billing"` -- an option that is its own key, with no description. */
  implicit def fromKey(key: String): ChoiceOption[String] = ChoiceOption(key, key, None)

  /** `"billing" -> "Payments, invoicing, refunds"`. */
  implicit def fromPair(pair: (String, String)): ChoiceOption[String] =
    ChoiceOption(pair._1, pair._1, Some(Content.text(pair._2)))

  /** `"billing" -> Content.obj(...)` for a structured description. */
  implicit def fromStructuredPair(pair: (String, Content)): ChoiceOption[String] =
    ChoiceOption(pair._1, pair._1, Some(pair._2))
}

/** Optional descriptions of what "true" and "false" mean for a [[Question.Noul]]. */
final case class NoulCriteria(ifTrue: Option[Content], ifFalse: Option[Content]) {
  private[jev] def toJson: ujson.Value = {
    val obj = ujson.Obj()
    ifTrue.foreach(c => obj("true") = c.json)
    ifFalse.foreach(c => obj("false") = c.json)
    obj
  }
}

object NoulCriteria {
  def apply(ifTrue: Content, ifFalse: Content): NoulCriteria = NoulCriteria(Some(ifTrue), Some(ifFalse))
  def whenTrue(ifTrue: Content): NoulCriteria                = NoulCriteria(Some(ifTrue), None)
  def whenFalse(ifFalse: Content): NoulCriteria              = NoulCriteria(None, Some(ifFalse))
}

private[jev] object Validate {

  /** Empty when `value` is usable as a key, otherwise a one-element list describing what is wrong. */
  def name(value: String, what: String): List[String] =
    if (value == null) List(s"$what is null")
    else if (value.trim.isEmpty) List(s"$what is empty")
    else Nil
}
