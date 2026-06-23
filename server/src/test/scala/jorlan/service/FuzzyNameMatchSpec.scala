/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import zio.test.*
import zio.test.Assertion.*

object FuzzyNameMatchSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Nothing] =
    suite("FuzzyNameMatchSpec")(
      // ─── score ────────────────────────────────────────────────────────────────
      test("empty query always matches with score 0") {
        assertTrue(FuzzyNameMatch.score("Alice Johnson", "") == Some(0))
      },
      test("exact substring match gives score 0") {
        assertTrue(FuzzyNameMatch.score("Alice Johnson", "alice") == Some(0))
      },
      test("query contained in name gives score 0") {
        assertTrue(FuzzyNameMatch.score("Roberto Leibman", "leib") == Some(0))
      },
      test("single character typo is within threshold for long query") {
        // "Roberto" vs "Roberto" → exact; "Robarto" → edit distance 1; threshold for 7 chars = max(1, 7/4)=1
        assertTrue(FuzzyNameMatch.score("Robarto Leibman", "roberto").isDefined)
      },
      test("no match for very different strings") {
        assertTrue(FuzzyNameMatch.score("Alice Johnson", "xzqwerty").isEmpty)
      },
      test("short query (1-3 chars) has threshold 1") {
        // "Bob" vs "Bab" → edit distance 1, threshold for 3 chars = max(1, 3/4)=1 → match
        assertTrue(FuzzyNameMatch.score("Bab Smith", "bob").isDefined)
      },
      test("query longer than any word in name with large distance gives None") {
        assertTrue(FuzzyNameMatch.score("Li Zhao", "verylongquerythatdoesnotmatch").isEmpty)
      },
      // ─── matches ──────────────────────────────────────────────────────────────
      test("matches returns true for substring") {
        assertTrue(FuzzyNameMatch.matches("John Smith", "smith"))
      },
      test("matches returns false for no match") {
        assertTrue(!FuzzyNameMatch.matches("John Smith", "xyzzy"))
      },
      // ─── rank ─────────────────────────────────────────────────────────────────
      test("rank returns all items for empty query") {
        val items = List("Alice", "Bob", "Charlie")
        assertTrue(FuzzyNameMatch.rank(items, "")(identity) == items)
      },
      test("rank filters out non-matching items") {
        val items = List("Alice Johnson", "Bob Smith", "Roberto Leibman")
        val result = FuzzyNameMatch.rank(items, "alice")(identity)
        assertTrue(result == List("Alice Johnson"))
      },
      test("rank sorts by score (closer match first)") {
        // "alice" is an exact substring of "alice jones", but "ali" is also a substring
        val items = List("Bobby", "Alice Jones", "Alicia")
        val result = FuzzyNameMatch.rank(items, "alice")(identity)
        // "Alice Jones" contains "alice" exactly (score 0); "Alicia" depends on distance
        assertTrue(result.headOption.contains("Alice Jones"))
      },
      test("rank returns empty list when nothing matches") {
        val items = List("Alice", "Bob", "Charlie")
        assertTrue(FuzzyNameMatch.rank(items, "xzqwerty")(identity).isEmpty)
      },
    )

}
