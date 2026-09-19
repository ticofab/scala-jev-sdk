package io.github.ticofab.jev

/** Token counts for one request. Jev bills on input tokens only. */
final case class Usage(inputTokens: Int, outputTokens: Int) {
  def totalTokens: Int = inputTokens + outputTokens
}

/** A complete response: the model that answered, the answers, and the token usage. */
final case class JevResponse(model: String, answers: Answers, usage: Usage)

/** One model available to the account, as returned by `GET /v1/models`. */
final case class ModelCard(name: String, description: String, releaseDate: String)
