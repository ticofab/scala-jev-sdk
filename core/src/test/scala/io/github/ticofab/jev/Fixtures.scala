package io.github.ticofab.jev

/** The request and response bodies from the TypeSafe API reference, used as the contract these tests hold us to. */
object Fixtures {

  val state: String = "Help! My payouts have been failing for 3 days."

  val isUrgent: Question.Noul = Question.noul(
    "is_urgent",
    "Does this convey urgency?",
    ifTrue = "Explicitly time-sensitive",
    ifFalse = "No urgency expressed"
  )

  val department: Question.Choice[String] = Question.choice(
    "department",
    "Which team should handle this?",
    "billing"   -> "Payments, invoicing, refunds",
    "technical" -> "Bugs, outages, integrations",
    "sales"     -> "Pricing, upgrades, new accounts"
  )

  val frustration: Question.Score =
    Question.score("frustration", "How frustrated is the customer?", "Calm", "Frustrated", "Very angry")

  val fullResponse: String =
    """{
      |  "model": "jev-latest",
      |  "answers": {
      |    "is_urgent": { "type": "noul", "noul": 0.92 },
      |    "department": {
      |      "type": "choice",
      |      "choice": "technical",
      |      "probabilities": { "billing": 0.08, "technical": 0.85, "sales": 0.07 },
      |      "confidence": 0.82
      |    },
      |    "frustration": {
      |      "type": "score",
      |      "score": 1.6,
      |      "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
      |      "probabilities": { "0": 0.05, "1": 0.3, "2": 0.65 },
      |      "confidence": 0.78
      |    }
      |  },
      |  "usage": { "input_tokens": 312, "output_tokens": 48 }
      |}""".stripMargin
}
