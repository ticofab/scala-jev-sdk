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

  // -- Images and boxes.

  test("a box question encodes with no criteria") {
    val json = encode(Fixtures.dogRequest)
    assertEquals(json("questions")("dog"), ujson.Obj("type" -> "box", "instructions" -> "Where is the dog in `photo`?"))
  }

  test("images encode as base64 assets keyed by their reference") {
    val asset = encode(Fixtures.dogRequest)("assets")("a3f1c2e8-found")

    assertEquals(asset("type").str, "image")
    assertEquals(asset("format").str, "jpg")
    assertEquals(java.util.Base64.getDecoder.decode(asset("data_base64").str).toSeq, Seq.fill[Byte](30)(7))
  }

  test("a request without images sends no assets field") {
    assert(!encode(JevRequest.of(Fixtures.state, Fixtures.isUrgent)).obj.contains("assets"))
  }

  test("an image request with every reference in place validates") {
    assertEquals(problemsOf(Fixtures.dogRequest), Nil)
  }

  test("an image referenced in criteria or mid-sentence counts as referenced") {
    val request = JevRequest
      .of(
        Fixtures.state,
        Question.noul("same", "Is it the dog in poster-77b0?"),
        Question.choice("which", "Which one?", "left" -> "the dog in photo-left-91c2")
      )
      .withImage("poster-77b0", Fixtures.photo)
      .withImage("photo-left-91c2", Fixtures.photo)

    assertEquals(problemsOf(request), Nil)
  }

  test("an image only mentioned in a key is not referenced, since keys are never replaced") {
    val request = JevRequest
      .of(Content.obj("a3f1" -> ujson.Str("the photo")), Question.choice("which", "Which?", "a3f1", "other"))
      .withImage("a3f1", Fixtures.photo)

    assertEquals(
      problemsOf(request),
      List("image 'a3f1' is not referenced in the state, any instructions or any criteria")
    )
  }

  test("a question's type is not a place a reference can hide") {
    val request = JevRequest.of(Fixtures.state, Question.noul("q", "Is it?")).withImage("noul", Fixtures.photo)
    assert(problemsOf(request).exists(_.contains("image 'noul' is not referenced")))
  }

  test("a box question without an image is reported") {
    assertEquals(
      problemsOf(JevRequest.of(Fixtures.state, Question.box("dog", "Where is the dog?"))),
      List("box question(s) dog need an image in the request")
    )
  }

  test("malformed base64 is reported, never thrown") {
    def problemFor(base64: String): List[String] =
      problemsOf(
        JevRequest.of("ref-1", Fixtures.isUrgent).withImage("ref-1", Image.fromBase64(ImageFormat.Png, base64))
      )

    assertEquals(problemFor("aGVsbG8="), Nil)
    assertEquals(problemFor(""), List("image 'ref-1' is empty"))
    assertEquals(problemFor(null), List("image 'ref-1' is empty"))
    assert(problemFor("data:image/png;base64,aGVsbG8=").head.contains("not standard base64"))
    assert(problemFor("aGVs\nbG8=").head.contains("not standard base64"))
    assert(problemFor("aGVsbG8").head.contains("not standard base64"))
  }

  test("an empty or oversized reference is reported") {
    val long    = "r" * 1025
    val request = JevRequest
      .of(Content.obj("a" -> ujson.Str(long)), Fixtures.isUrgent)
      .withImage("", Fixtures.photo)
      .withImage(long, Fixtures.photo)

    assertEquals(
      problemsOf(request),
      List("an image reference is empty", s"image reference '${"r" * 40}...' is over 1024 bytes")
    )
  }

  test("an image over 16 MiB is reported without decoding it") {
    val huge    = Image.fromBase64(ImageFormat.Png, "A" * (22 * 1024 * 1024))
    val request = JevRequest.of("ref-1", Fixtures.isUrgent).withImage("ref-1", huge)

    assertEquals(problemsOf(request), List(s"image 'ref-1' is ${huge.sizeInBytes} bytes, the maximum is 16777216"))
  }

  test("an image never prints its data") {
    assertEquals(Fixtures.photo.toString, "Image(Jpeg, 30 bytes)")
  }

  test("a valid request validates to itself") {
    val request = JevRequest.of(Fixtures.state, Fixtures.isUrgent, Fixtures.department)
    assertEquals(request.validate, Right(request))
  }
}
