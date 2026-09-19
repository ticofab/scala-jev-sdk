package examples

import io.github.ticofab.jev._

/** The questions used by every example: one triage pass over an inbound support message. */
object Triage {

  sealed trait Team
  object Team {
    case object Billing   extends Team
    case object Technical extends Team
    case object Sales     extends Team
  }

  val isUrgent: Question.Noul = Question.noul(
    "is_urgent",
    "Does this convey urgency?",
    ifTrue = "Explicitly time-sensitive, or describes an ongoing loss",
    ifFalse = "No urgency expressed"
  )

  /** The answer comes back as a `Team`, not as a string to re-parse. */
  val department: Question.Choice[Team] = Question.choiceOf[Team](
    "department",
    "Which team should handle this?",
    ChoiceOption(Team.Billing, "billing", "Payments, invoicing, refunds"),
    ChoiceOption(Team.Technical, "technical", "Bugs, outages, integrations"),
    ChoiceOption(Team.Sales, "sales", "Pricing, upgrades, new accounts")
  )

  val frustration: Question.Score = Question.score(
    "frustration",
    "How frustrated is the customer?",
    "Calm and neutral",
    "Concerned but civil",
    "Very angry or using strong language"
  )

  val message: String = "Help! My payouts have been failing for 3 days and nobody has replied."

  def report(response: JevResponse): String = {
    val urgent = response.answers(isUrgent)
    val team   = response.answers(department)
    val mood   = response.answers(frustration)

    s"""urgent      : ${urgent.probability.percent.round}% (${if (urgent.isYes()) "yes" else "no"})
       |team        : ${team.choice} (confidence ${team.confidence})
       |  ranked    : ${team.ranked.map { case (t, p) => s"$t=$p" }.mkString(", ")}
       |frustration : ${mood.score} / ${mood.maxLevel} -- "${mood.nearestLabel}"
       |usage       : ${response.usage.inputTokens} input tokens""".stripMargin
  }
}
