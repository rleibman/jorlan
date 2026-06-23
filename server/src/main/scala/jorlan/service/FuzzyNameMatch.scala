/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

/** Word-level fuzzy matching for contact name search.
  *
  * Returns a match score (lower = better) or None when no word in the display name is close enough to the query. A
  * score of 0 means an exact substring match; positive scores reflect the minimum Levenshtein distance found among the
  * individual words.
  *
  * Threshold: max(1, queryLength / 4), so queries of 1–3 chars allow edit distance 1, queries of 8 chars allow 2, etc.
  */
object FuzzyNameMatch {

  /** Score `displayName` against `query`. `None` = no match. */
  def score(
    displayName: String,
    query:       String,
  ): Option[Int] = {
    val q = query.toLowerCase.trim
    if (q.isEmpty) return Some(0)
    val name = displayName.toLowerCase.trim
    if (name.contains(q)) return Some(0)
    val words = name.split("\\s+")
    val threshold = math.max(1, q.length / 4)
    val minDist = words.map(w => levenshtein(w, q)).min
    if (minDist <= threshold) Some(minDist) else None
  }

  def matches(
    displayName: String,
    query:       String,
  ): Boolean =
    score(displayName, query).isDefined

  /** Compare and rank a list of users by fuzzy score, dropping non-matches. */
  def rank[A](
    xs:    List[A],
    query: String,
  )(
    name: A => String,
  ): List[A] = {
    val q = query.trim
    if (q.isEmpty) return xs
    xs
      .flatMap(a => score(name(a), q).map(s => (s, a)))
      .sortBy(_._1)
      .map(_._2)
  }

  private def levenshtein(
    s: String,
    t: String,
  ): Int = {
    val m = s.length
    val n = t.length
    val dp = Array.ofDim[Int](m + 1, n + 1)
    for (i <- 0 to m) dp(i)(0) = i
    for (j <- 0 to n) dp(0)(j) = j
    for {
      i <- 1 to m
      j <- 1 to n
    } {
      dp(i)(j) =
        if (s(i - 1) == t(j - 1)) dp(i - 1)(j - 1)
        else 1 + math.min(dp(i - 1)(j - 1), math.min(dp(i - 1)(j), dp(i)(j - 1)))
    }
    dp(m)(n)
  }

}
