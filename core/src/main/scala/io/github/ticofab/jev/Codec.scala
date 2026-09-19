package io.github.ticofab.jev

/** Turning a Jev HTTP response body into a [[JevResponse]]. */
private[jev] object Codec {

  def parseResponse(body: String, questions: Seq[Question]): Either[JevError, JevResponse] =
    read(body).flatMap { json =>
      val decoded = for {
        model      <- Decode.string(json, "model")
        answersObj <- Decode.field(json, "answers")
        answers    <- decodeAnswers(answersObj, questions)
        usage      <- decodeUsage(json)
      } yield JevResponse(model, new Answers(answers), usage)

      decoded.left.map(reason => JevError.Decoding(reason, body))
    }

  def parseModels(body: String): Either[JevError, Seq[ModelCard]] =
    read(body).flatMap { json =>
      val entries = json.arrOpt
        .map(_.toSeq)
        .orElse(
          json.objOpt.flatMap(fields => fields.get("data").orElse(fields.get("models"))).flatMap(_.arrOpt).map(_.toSeq)
        )
        .toRight("expected an array of models, or an object with a 'data' or 'models' array")

      entries
        .flatMap(values => Decode.sequence(values.map(decodeModelCard)))
        .left
        .map(reason => JevError.Decoding(reason, body))
    }

  private def read(body: String): Either[JevError, ujson.Value] =
    scala.util.Try(ujson.read(body)).toEither.left.map { failure =>
      JevError.Decoding(s"body is not valid JSON (${failure.getMessage})", body)
    }

  private def decodeAnswers(json: ujson.Value, questions: Seq[Question]): Either[String, Map[String, Answer]] =
    json.objOpt.toRight("field 'answers' is not an object").flatMap { answers =>
      val decoded = questions.map { question =>
        answers.get(question.name) match {
          case None        => Left(s"no answer for question '${question.name}'")
          case Some(value) =>
            question.decode(value).left.map(reason => s"answer '${question.name}': $reason")
        }
      }
      Decode.sequence(decoded).map(values => questions.map(_.name).zip(values).toMap)
    }

  private def decodeUsage(json: ujson.Value): Either[String, Usage] =
    json.objOpt.flatMap(_.get("usage")) match {
      case None        => Right(Usage(0, 0))
      case Some(usage) =>
        for {
          in  <- Decode.double(usage, "input_tokens")
          out <- Decode.double(usage, "output_tokens")
        } yield Usage(in.toInt, out.toInt)
    }

  private def decodeModelCard(json: ujson.Value): Either[String, ModelCard] =
    for {
      name        <- Decode.string(json, "name")
      description <- Right(json.objOpt.flatMap(_.get("description")).flatMap(_.strOpt).getOrElse(""))
      released    <- Right(json.objOpt.flatMap(_.get("release_date")).flatMap(_.strOpt).getOrElse(""))
    } yield ModelCard(name, description, released)
}
