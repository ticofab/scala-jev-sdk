package io.github.ticofab.jev

/**
 * One call to `POST /v1/systemone`: some state, and the questions to answer about it.
 *
 * Build one directly when the questions are assembled dynamically; the [[JevClient.ask]] overloads build it for you in
 * the common case.
 *
 * {{{
 * val request = JevRequest(state = "Help! My payouts have been failing for 3 days.", questions = Seq(isUrgent, team))
 *   .withModel("jev-1.13.0")
 * }}}
 */
final case class JevRequest(
    state: Content,
    questions: Seq[Question],
    model: Option[String] = None
) {

  /**
   * Everything wrong with this request, empty when it is valid.
   *
   * Collects the problems of every question as well, so a single call reports all of them at once instead of stopping
   * at the first.
   */
  def problems: List[String] = {
    val names      = questions.map(_.name)
    val duplicates = names.diff(names.distinct).distinct

    (if (questions.isEmpty) List("a request needs at least one question") else Nil) :::
      (if (duplicates.nonEmpty) List(s"question names must be unique, repeated: ${duplicates.mkString(", ")}")
       else Nil) :::
      questions.toList.flatMap(_.problems)
  }

  /** This request, if it is valid, or every reason it is not. */
  def validate: Either[JevError, JevRequest] =
    problems match {
      case Nil      => Right(this)
      case problems => Left(JevError.InvalidRequest(problems))
    }

  def withModel(value: String): JevRequest      = copy(model = Some(value))
  def withQuestion(value: Question): JevRequest = copy(questions = questions :+ value)

  /** The request body as it goes over the wire, with `model` resolved against the client default. */
  private[jev] def toJson(defaultModel: String): ujson.Value = {
    val questionFields = ujson.Obj()
    questions.foreach(q => questionFields(q.name) = q.toJson)
    ujson.Obj(
      "state"     -> state.json,
      "model"     -> ujson.Str(model.getOrElse(defaultModel)),
      "questions" -> questionFields
    )
  }
}

object JevRequest {

  /** A request built from a varargs list of questions. */
  def of(state: Content, questions: Question*): JevRequest = JevRequest(state, questions, None)
}
