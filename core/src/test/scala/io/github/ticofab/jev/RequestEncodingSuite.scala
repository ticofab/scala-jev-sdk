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

  // -- Building questions from a collection rather than from literals. The case-class constructors take a Seq, so
  // -- a dataset-sized taxonomy needs no varargs gymnastics. These pin that the three routes are interchangeable.

  test("a score question builds from a Seq of levels, three ways") {
    val labels: Seq[String]  = List("Calm", "Frustrated", "Very angry")
    val levels: Seq[Content] = labels.map(Content.text)

    val fromLiterals =
      Question.score("frustration", "How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
    val fromSplat     = Question.score("frustration", "How frustrated is the customer?", levels*)
    val fromCaseClass = Question.Score("frustration", "How frustrated is the customer?", levels)

    val encoded = List(fromLiterals, fromSplat, fromCaseClass).map(q => encode(JevRequest.of(Fixtures.state, q)))

    assertEquals(encoded.distinct.size, 1, "the three routes must encode identically")
    assertEquals(
      encoded.head("questions")("frustration")("criteria").arr.map(_.str).toList,
      labels.toList
    )
  }

  test("a choice question builds from a Seq of options, three ways") {
    val rows: Seq[(String, String)] = List(
      "billing"   -> "Payments, invoicing, refunds",
      "technical" -> "Bugs, outages, integrations"
    )
    val options: Seq[ChoiceOption[String]] = rows.map(ChoiceOption.fromPair)

    // Literals convert one argument at a time via ChoiceOption.fromPair; a splatted Seq does not, which is why
    // `options` below is mapped explicitly. Writing `rows*` here would not compile.
    val fromLiterals = Question.choice(
      "department",
      "Which team?",
      "billing"   -> "Payments, invoicing, refunds",
      "technical" -> "Bugs, outages, integrations"
    )
    val fromSplat     = Question.choice("department", "Which team?", options*)
    val fromCaseClass = Question.Choice("department", "Which team?", options)

    val encoded = List(fromLiterals, fromSplat, fromCaseClass).map(q => encode(JevRequest.of(Fixtures.state, q)))

    assertEquals(encoded.distinct.size, 1, "the three routes must encode identically")

    val criteria = encoded.head("questions")("department")("criteria")
    assertEquals(criteria.obj.keys.toList, List("billing", "technical"))
    assertEquals(criteria("billing").str, "Payments, invoicing, refunds")
  }

  test("a typed choice builds from a Seq, keeping the option values") {
    sealed trait Team
    case object Billing   extends Team
    case object Technical extends Team

    val options: Seq[ChoiceOption[Team]] =
      List(Billing -> "billing", Technical -> "technical").map((team, key) => ChoiceOption(team, key))

    val fromSplat     = Question.choiceOf[Team]("department", "Which team?", options*)
    val fromCaseClass = Question.Choice[Team]("department", "Which team?", options)

    assertEquals(fromSplat, fromCaseClass)
    assertEquals(fromCaseClass.options.map(_.value), List(Billing, Technical): List[Team])
  }

  test("questions themselves can come from a Seq") {
    val questions: Seq[Question] = List(Fixtures.isUrgent, Fixtures.department, Fixtures.frustration)

    val fromSeq     = JevRequest(Fixtures.state, questions)
    val fromVarargs = JevRequest.of(Fixtures.state, Fixtures.isUrgent, Fixtures.department, Fixtures.frustration)

    assertEquals(encode(fromSeq), encode(fromVarargs))
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

  test("a score question with more than ten levels is reported") {
    val levels = (1 to 11).map(i => Content.text(s"level $i"))
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Question.Score("x", "How much?", levels))),
      List("score question 'x' has 11 levels, the maximum is 10")
    )
    assertEquals(problemsOf(JevRequest.of(Fixtures.state, Question.Score("x", "How much?", levels.take(10)))), Nil)
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
