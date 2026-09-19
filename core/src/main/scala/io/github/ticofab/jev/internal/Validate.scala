package io.github.ticofab.jev
package internal

private[jev] object Validate {

  /** Empty when `value` is usable as a key, otherwise a one-element list describing what is wrong. */
  def name(value: String, what: String): List[String] =
    if (value == null) List(s"$what is null")
    else if (value.trim.isEmpty) List(s"$what is empty")
    else Nil
}
