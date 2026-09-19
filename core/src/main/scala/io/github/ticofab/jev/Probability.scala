package io.github.ticofab.jev

/**
 * A probability, guaranteed to be within `[0.0, 1.0]`.
 *
 * Every number Jev returns that is a probability -- a noul's answer, a distribution entry, a confidence -- is wrapped
 * in this type, so a probability can never silently be read as a percentage or as an unbounded score.
 */
final case class Probability(value: Double) extends Ordered[Probability] {
  require(
    value >= 0.0 && value <= 1.0 && !value.isNaN,
    s"a probability must be within [0.0, 1.0], got $value"
  )

  /** The probability as a percentage in `[0.0, 100.0]`. */
  def percent: Double = value * 100.0

  /** True if this probability is at or above `threshold` (default `0.5`). */
  def atLeast(threshold: Double = 0.5): Boolean = value >= threshold

  def compare(that: Probability): Int = java.lang.Double.compare(value, that.value)

  override def toString: String = f"$value%.4f"
}

object Probability {

  val Zero: Probability = Probability(0.0)
  val One: Probability  = Probability(1.0)

  /** Clamps any double into `[0.0, 1.0]`. `NaN` becomes `0.0`. */
  def clamp(value: Double): Probability =
    if (value.isNaN) Zero else Probability(math.max(0.0, math.min(1.0, value)))

  /** Validates a double as a probability, describing the problem instead of throwing. */
  def from(value: Double): Either[String, Probability] =
    if (value.isNaN) Left("probability is NaN")
    else if (value < 0.0 || value > 1.0) Left(s"probability $value is outside [0.0, 1.0]")
    else Right(new Probability(value))

  implicit val ordering: Ordering[Probability] = Ordering.by(_.value)
}
