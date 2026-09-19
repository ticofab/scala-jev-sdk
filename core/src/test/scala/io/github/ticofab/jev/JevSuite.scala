package io.github.ticofab.jev

/**
 * Shared unwrapping for tests.
 *
 * The SDK hands back `Either` everywhere, which is the point -- but a test that has already asserted the happy path
 * should not have to keep pattern matching. These turn a `Left` into a test failure with a useful message.
 */
abstract class JevSuite extends munit.FunSuite {

  /** The right-hand side, or a failed test naming the error. */
  def ok[A](result: Either[JevError, A])(implicit loc: munit.Location): A =
    result.fold(error => fail(s"expected success, got ${error.getMessage}"), identity)

  /** The left-hand side, or a failed test. */
  def err[A](result: Either[JevError, A])(implicit loc: munit.Location): JevError =
    result.fold(identity, value => fail(s"expected a failure, got $value"))

  implicit class ResponseOps(private val response: JevResponse) {

    /** The answer to `question`, or a failed test. Typed by the question, as in real use. */
    def answer(question: Question)(implicit loc: munit.Location): question.Out =
      response.answers.get(question).getOrElse(fail(s"no answer for question '${question.name}'"))
  }
}
