package io.github.ticofab.jev

class ResponseDecodingSuite extends munit.FunSuite {

  private val questions = Seq(Fixtures.isUrgent, Fixtures.department, Fixtures.frustration)

  private def decode(body: String, qs: Seq[Question] = questions): Either[JevError, JevResponse] =
    Codec.parseResponse(body, qs)

  test("the documented response decodes into typed answers") {
    val response = decode(Fixtures.fullResponse).fold(throw _, identity)

    assertEquals(response.model, "jev-latest")
    assertEquals(response.usage, Usage(312, 48))
    assertEquals(response.usage.totalTokens, 360)

    val urgent = response.answers(Fixtures.isUrgent)
    assertEquals(urgent.probability, Probability(0.92))
    assert(urgent.isYes())
    assert(!urgent.isYes(threshold = 0.95))

    val team = response.answers(Fixtures.department)
    assertEquals(team.choice, "technical")
    assertEquals(team.probabilityOf("billing"), Probability(0.08))
    assertEquals(team.confidence, Probability(0.82))
    assertEquals(team.ranked.map(_._1), Seq("technical", "billing", "sales"))
    assertEquals(team.choiceIfConfident(threshold = 0.9), None)
    assertEquals(team.choiceIfConfident(threshold = 0.8), Some("technical"))

    val frustration = response.answers(Fixtures.frustration)
    assertEquals(frustration.score, 1.6)
    assertEquals(frustration.nearestLevel, 2)
    assertEquals(frustration.nearestLabel, "Very angry")
    assertEquals(frustration.mostLikelyLevel, 2)
    assertEquals(frustration.maxLevel, 2)
    assertEqualsDouble(frustration.normalized, 0.8, 1e-9)
  }

  test("choice options keep the order they were declared in") {
    val response = decode(Fixtures.fullResponse).fold(throw _, identity)
    assertEquals(
      response.answers(Fixtures.department).probabilities.keys.toList,
      List("billing", "technical", "sales")
    )
  }

  test("a choice answer comes back as the caller's own type") {
    sealed trait Team
    case object Billing   extends Team
    case object Technical extends Team

    val question = Question.choiceOf[Team](
      "department",
      "Which team?",
      ChoiceOption(Billing, "billing", "Payments"),
      ChoiceOption(Technical, "technical", "Bugs")
    )
    val body =
      """{"model":"jev-latest","answers":{"department":{"type":"choice","choice":"technical",
        |"probabilities":{"billing":0.1,"technical":0.9},"confidence":0.8}},
        |"usage":{"input_tokens":1,"output_tokens":0}}""".stripMargin

    val answer: ChoiceAnswer[Team] = decode(body, Seq(question)).fold(throw _, identity).answers(question)
    assertEquals(answer.choice, Technical: Team)
    assertEquals(answer.probabilityOf(Billing), Probability(0.1))
  }

  test("a missing legend falls back to the levels the question declared") {
    val body =
      """{"model":"jev-latest","answers":{"frustration":{"type":"score","score":0.2,
        |"probabilities":{"0":0.8,"1":0.2,"2":0.0},"confidence":0.7}},
        |"usage":{"input_tokens":1,"output_tokens":0}}""".stripMargin

    val answer = decode(body, Seq(Fixtures.frustration)).fold(throw _, identity).answers(Fixtures.frustration)
    assertEquals(answer.nearestLabel, "Calm")
  }

  test("a missing answer is reported by name") {
    val body = """{"model":"jev-latest","answers":{},"usage":{"input_tokens":1,"output_tokens":0}}"""
    decode(body, Seq(Fixtures.isUrgent)) match {
      case Left(JevError.Decoding(reason, _)) => assert(reason.contains("no answer for question 'is_urgent'"), reason)
      case other                              => fail(s"expected a decoding error, got $other")
    }
  }

  test("an out-of-range probability is a decoding error, not a silent clamp") {
    val body =
      """{"model":"jev-latest","answers":{"is_urgent":{"type":"noul","noul":1.4}},
        |"usage":{"input_tokens":1,"output_tokens":0}}""".stripMargin

    decode(body, Seq(Fixtures.isUrgent)) match {
      case Left(JevError.Decoding(reason, _)) => assert(reason.contains("outside [0.0, 1.0]"), reason)
      case other                              => fail(s"expected a decoding error, got $other")
    }
  }

  test("an unknown choice key is a decoding error") {
    val body =
      """{"model":"jev-latest","answers":{"department":{"type":"choice","choice":"legal",
        |"probabilities":{"legal":1.0},"confidence":0.9}},"usage":{"input_tokens":1,"output_tokens":0}}""".stripMargin

    decode(body, Seq(Fixtures.department)) match {
      case Left(JevError.Decoding(reason, _)) => assert(reason.contains("unknown option 'legal'"), reason)
      case other                              => fail(s"expected a decoding error, got $other")
    }
  }

  test("a body that is not JSON is a decoding error carrying the body") {
    decode("<html>502 Bad Gateway</html>") match {
      case Left(JevError.Decoding(_, body)) => assertEquals(body, "<html>502 Bad Gateway</html>")
      case other                            => fail(s"expected a decoding error, got $other")
    }
  }

  test("answers can also be read untyped, by name") {
    val response = decode(Fixtures.fullResponse).fold(throw _, identity)
    assertEquals(response.answers.names, Set("is_urgent", "department", "frustration"))
    assertEquals(response.answers.raw("is_urgent").map(_.questionType), Some("noul"))
    assertEquals(response.answers.raw("nope"), None)
  }

  test("a noul's confidence is its distance from a coin flip") {
    assertEqualsDouble(NoulAnswer(Probability(0.92)).confidence.value, 0.84, 1e-9)
    assertEqualsDouble(NoulAnswer(Probability(0.5)).confidence.value, 0.0, 1e-9)
    assertEqualsDouble(NoulAnswer(Probability(0.0)).confidence.value, 1.0, 1e-9)
  }

  test("models decode from either an array or a wrapper object") {
    val card     = """{"name":"jev-1.13.0","description":"System One","release_date":"2026-09-15"}"""
    val expected = Seq(ModelCard("jev-1.13.0", "System One", "2026-09-15"))

    assertEquals(Codec.parseModels(s"[$card]"), Right(expected))
    assertEquals(Codec.parseModels(s"""{"data":[$card]}"""), Right(expected))
    assertEquals(Codec.parseModels(s"""{"models":[$card]}"""), Right(expected))
  }
}
