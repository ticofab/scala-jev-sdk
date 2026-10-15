package io.github.ticofab.jev

import scala.collection.immutable.ListMap

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
 *
 * @param assets
 *   images keyed by the reference that stands in for them in the state and the questions; see [[withImage]]
 * @param longContext
 *   raises the context limits from 64k tokens per request and 32k per question to 262k each; see [[withLongContext]]
 */
final case class JevRequest(
    state: Content,
    questions: Seq[Question],
    model: Option[String] = None,
    assets: ListMap[String, Image] = ListMap.empty,
    longContext: Boolean = false
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
      questions.toList.flatMap(_.problems) :::
      assetProblems
  }

  /** True when the request needs the images danger-zone header: it carries an image or asks for a box. */
  private[jev] def usesImages: Boolean =
    assets.nonEmpty || questions.exists(_.isInstanceOf[Question.Box])

  private def assetProblems: List[String] = {
    val refBytes = assets.keys.toList.map(ref => ref -> ref.getBytes("UTF-8").length)
    val texts    = JevRequest.texts(state.json) ++ questions.flatMap(q => JevRequest.questionTexts(q.toJson))
    val encoded  = assets.values.map(_.base64.length.toLong).sum

    val boxWithoutImage =
      questions.collect { case box: Question.Box => box.name }.toList match {
        case names if names.nonEmpty && assets.isEmpty =>
          List(s"box question(s) ${names.mkString(", ")} need an image in the request")
        case _ => Nil
      }

    val perRef = assets.toList.flatMap { case (ref, image) =>
      if (ref.isEmpty) List("an image reference is empty")
      else
        (if (ref.getBytes("UTF-8").length > JevRequest.MaxReferenceBytes)
           List(s"image reference '${ref.take(40)}...' is over ${JevRequest.MaxReferenceBytes} bytes")
         else Nil) :::
          (if (!texts.exists(_.contains(ref)))
             List(s"image '$ref' is not referenced in the state, any instructions or any criteria")
           else Nil) :::
          image.problems(ref)
    }

    val allRefs =
      if (refBytes.map(_._2).sum > JevRequest.MaxReferencesBytes)
        List(s"image references add up to over ${JevRequest.MaxReferencesBytes} bytes")
      else Nil

    val body =
      if (encoded > JevRequest.MaxBodyBytes)
        List(s"images add up to $encoded bytes of base64, over the ${JevRequest.MaxBodyBytes} byte request limit")
      else Nil

    boxWithoutImage ::: perRef ::: allRefs ::: body
  }

  /** This request, if it is valid, or every reason it is not. */
  def validate: Either[JevError, JevRequest] =
    problems match {
      case Nil      => Right(this)
      case problems => Left(JevError.InvalidRequest(problems))
    }

  def withModel(value: String): JevRequest      = copy(model = Some(value))
  def withQuestion(value: Question): JevRequest = copy(questions = questions :+ value)

  /**
   * Attaches `image` under `ref`. A danger-zone feature, `jev-1.13.0` and later.
   *
   * Jev swaps the image in wherever `ref` appears as text in the state, the instructions or the criteria -- the whole
   * value, or inside a sentence. Object keys (question names, option keys) are never replaced, and answers echo the
   * reference, not the image. Matching is plain text, so pick references that cannot occur by accident: `cat` would
   * also match inside `category`.
   *
   * {{{
   * JevRequest
   *   .of(Content.obj("photo" -> ujson.Str("a3f1-found")), Question.noul("same_dog", "Is `photo` the dog in b7d4-poster?"))
   *   .withImage("a3f1-found", Image.jpeg(found))
   *   .withImage("b7d4-poster", Image.jpeg(poster))
   * }}}
   *
   * The client sends the `TypeSafe-Danger-Zone-Images` header for any request that carries an image or a box question,
   * since the API rejects one without it.
   */
  def withImage(ref: String, image: Image): JevRequest = copy(assets = assets.updated(ref, image))

  /**
   * Raises the context limits to 262k tokens, per request and per question, by sending the
   * `TypeSafe-Danger-Zone-Long-Context` header. A danger-zone feature: priced the same, but slower and less accurate.
   */
  def withLongContext: JevRequest = copy(longContext = true)

  /** The request body as it goes over the wire, with `model` resolved against the client default. */
  private[jev] def toJson(defaultModel: String): ujson.Value = {
    val questionFields = ujson.Obj()
    questions.foreach(q => questionFields(q.name) = q.toJson)
    val body = ujson.Obj(
      "state"     -> state.json,
      "model"     -> ujson.Str(model.getOrElse(defaultModel)),
      "questions" -> questionFields
    )
    if (assets.nonEmpty) body("assets") = ujson.Obj.from(assets.map { case (ref, image) => ref -> image.toJson })
    body
  }
}

object JevRequest {

  /** A request built from a varargs list of questions. */
  def of(state: Content, questions: Question*): JevRequest = JevRequest(state, questions, None)

  private val MaxReferenceBytes  = 1024
  private val MaxReferencesBytes = 65536
  private val MaxBodyBytes       = 32L * 1024 * 1024

  /** Every string value in `json`, the places an image reference is replaced. Keys are not among them. */
  private def texts(json: ujson.Value): Seq[String] = json match {
    case ujson.Str(s)   => Seq(s)
    case ujson.Arr(vs)  => vs.toSeq.flatMap(texts)
    case ujson.Obj(kvs) => kvs.values.toSeq.flatMap(texts)
    case _              => Nil
  }

  /** The texts of a question's instructions and criteria, leaving out its `type` discriminator. */
  private def questionTexts(json: ujson.Value): Seq[String] =
    json.objOpt.fold(Seq.empty[String])(fields => fields.view.filterKeys(_ != "type").values.toSeq.flatMap(texts))
}
