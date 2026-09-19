package io.github.ticofab.jev

class RequestEncodingSuite extends JevSuite {

  private def encode(request: JevRequest): ujson.Value = request.toJson("jev-latest")

  test("a noul question encodes to the documented shape") {
    val json = encode(JevRequest.of(Fixtures.state, Fixtures.isUrgent))

    assertEquals(json("model").str, "jev-latest")
    assertEquals(json("state").str, Fixtures.state)
    assertEquals(json("questions")("is_urgent")("type").str, "noul")
    assertEquals(json("questions")("is_urgent")("instructions").str, "Does this convey urgency?")
    assertEquals(json("questions")("is_urgent")("criteria")("true").str, "Explicitly time-sensitive")
    assertEquals(json("questions")("is_urgent")("criteria")("false").str, "No urgency expressed")
  }

  test("a noul question without criteria omits the field entirely") {
    val json = encode(JevRequest.of(Fixtures.state, Question.noul("plain", "Is it?")))
    assert(!json("questions")("plain").obj.contains("criteria"))
  }

  test("a choice question encodes its options as a criteria object") {
    val json     = encode(JevRequest.of(Fixtures.state, Fixtures.department))
    val criteria = json("questions")("department")("criteria")

    assertEquals(criteria.obj.keys.toList, List("billing", "technical", "sales"))
    assertEquals(criteria("billing").str, "Payments, invoicing, refunds")
  }

  test("a choice option given as a bare key encodes a null description") {
    val json = encode(JevRequest.of(Fixtures.state, Question.choice("q", "Which?", "yes", "no")))
    assertEquals(json("questions")("q")("criteria")("yes"), ujson.Null)
  }

  test("a score question encodes its levels as an ordered array") {
    val json = encode(JevRequest.of(Fixtures.state, Fixtures.frustration))
    assertEquals(
      json("questions")("frustration")("criteria").arr.map(_.str).toList,
      List("Calm", "Frustrated", "Very angry")
    )
  }

  test("state and instructions accept structured JSON") {
    val question = Question.noul(
      "consistent",
      Content.obj("question" -> ujson.Str("Do the domains match?"), "field" -> ujson.Str("from"))
    )
    val json = encode(JevRequest.of(Content.obj("from" -> ujson.Str("billing@acme.test")), question))

    assertEquals(json("state")("from").str, "billing@acme.test")
    assertEquals(json("questions")("consistent")("instructions")("field").str, "from")
  }

  test("a per-request model overrides the client default") {
    val json = encode(JevRequest.of(Fixtures.state, Fixtures.isUrgent).withModel("jev-1.13.0"))
    assertEquals(json("model").str, "jev-1.13.0")
  }

  private def problemsOf(request: JevRequest): List[String] = request.validate match {
    case Left(JevError.InvalidRequest(problems)) => problems
    case Left(other)                             => fail(s"expected an InvalidRequest, got $other")
    case Right(_)                                => Nil
  }

  test("building a question never throws, however wrong it is") {
    Question.score("x", "How much?", "Only one")
    Question.choice("x", "Which?")
    Question.choice("x", "Which?", "a" -> "one", "a" -> "two")
    Question.noul("", "Is it?")
    JevRequest(Fixtures.state, Nil)
  }

  test("a score question with fewer than two levels is reported") {
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Question.score("x", "How much?", "Only one"))),
      List("score question 'x' needs at least 2 levels, got 1")
    )
  }

  test("a choice question with no options is reported") {
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Question.choice("x", "Which?"))),
      List("choice question 'x' needs at least one option")
    )
  }

  test("duplicate option keys are reported") {
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Question.choice("x", "Which?", "a" -> "one", "a" -> "two"))),
      List("choice question 'x' has duplicate option keys: a")
    )
  }

  test("an empty question name is reported") {
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Question.noul("", "Is it?"))),
      List("question name is empty")
    )
  }

  test("a request with no questions is reported") {
    assertEquals(problemsOf(JevRequest(Fixtures.state, Nil)), List("a request needs at least one question"))
  }

  test("duplicate question names are reported") {
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Fixtures.isUrgent, Fixtures.isUrgent)),
      List("question names must be unique, repeated: is_urgent")
    )
  }

  test("every problem is reported at once, not just the first") {
    val request = JevRequest.of(
      Fixtures.state,
      Question.noul("", "Is it?"),
      Question.score("mood", "How?", "Only one"),
      Question.choice("team", "Which?")
    )

    assertEquals(
      problemsOf(request),
      List(
        "question name is empty",
        "score question 'mood' needs at least 2 levels, got 1",
        "choice question 'team' needs at least one option"
      )
    )
  }

  test("a valid request validates to itself") {
    val request = JevRequest.of(Fixtures.state, Fixtures.isUrgent, Fixtures.department)
    assertEquals(request.validate, Right(request))
  }
}
