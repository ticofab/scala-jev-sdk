package io.github.ticofab.jev

/**
 * A probability, guaranteed to be within `[0.0, 1.0]`.
 *
 * Every number Jev returns that is a probability -- a noul's answer, a distribution entry, a confidence -- is wrapped
 * in this type, so a probability can never silently be read as a percentage or as an unbounded score.
 *
 * The constructor is private: a `Probability` can only be built through [[Probability.clamp]], which is total, or
 * [[Probability.from]], which reports the problem rather than throwing. That keeps the range invariant real without
 * anything ever failing at runtime.
 */
sealed abstract case class Probability private (value: Double) extends Ordered[Probability] {

  /** The probability as a percentage in `[0.0, 100.0]`. */
  def percent: Double = value * 100.0

  /** True if this probability is at or above `threshold` (default `0.5`). */
  def atLeast(threshold: Double = 0.5): Boolean = value >= threshold

  def compare(that: Probability): Int = java.lang.Double.compare(value, that.value)

  override def toString: String = f"$value%.4f"
}

object Probability {

  private def make(value: Double): Probability = new Probability(value) {}

  val Zero: Probability = make(0.0)
  val One: Probability  = make(1.0)

  /** Clamps any double into `[0.0, 1.0]`. `NaN` becomes `0.0`. Never fails. */
  def clamp(value: Double): Probability =
    if (value.isNaN) Zero else make(math.max(0.0, math.min(1.0, value)))

  /** Validates a double as a probability, describing the problem instead of throwing. */
  def from(value: Double): Either[String, Probability] =
    if (value.isNaN) Left("probability is NaN")
    else if (value < 0.0 || value > 1.0) Left(s"probability $value is outside [0.0, 1.0]")
    else Right(make(value))

  implicit val ordering: Ordering[Probability] = Ordering.by(_.value)
}
