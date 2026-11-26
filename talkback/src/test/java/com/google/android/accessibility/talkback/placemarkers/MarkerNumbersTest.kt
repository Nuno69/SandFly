/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.placemarkers

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerNumbersTest {

  private fun marker(number: Int, lastUsed: Long = 0, text: String? = "item $number") =
    StoredMarker(number, lastUsed, "android.widget.TextView", 0, text, null, null, false, emptyList(), null, null, null)

  private fun assign(markers: List<StoredMarker>, existing: Int?, requested: Int?) =
    MarkerNumbers.assign(markers, existing, requested, now = 100) { marker(it, 100, "new") }

  @Test
  fun takesTheFirstFreeNumber() {
    val (outcome, markers) = assign(listOf(marker(1), marker(3)), existing = null, requested = null)
    assertEquals(SetOutcome.Placed(2), outcome)
    assertEquals(listOf(1, 2, 3), markers.map { it.number })
  }

  @Test
  fun replacesTheMarkerUsedLongestAgoWhenAllAreSet() {
    val all = (1..10).map { marker(it, lastUsed = if (it == 4) 5L else 50L) }
    val (outcome, markers) = assign(all, existing = null, requested = null)
    assertEquals(SetOutcome.Moved(4, "item 4"), outcome)
    assertEquals("new", markers.single { it.number == 4 }.text)
    assertEquals(10, markers.size)
  }

  @Test
  fun anItemWithAMarkerKeepsIt() {
    val (outcome, markers) = assign(listOf(marker(2)), existing = 2, requested = null)
    assertEquals(SetOutcome.AlreadyMarked(2), outcome)
    assertEquals("item 2", markers.single().text)
    assertEquals(100L, markers.single().lastUsed)
    assertEquals(SetOutcome.AlreadyMarked(2), assign(listOf(marker(2)), existing = 2, requested = 2).first)
  }

  @Test
  fun aNumberAskedForMovesTheItemsMarker() {
    val (outcome, markers) = assign(listOf(marker(2), marker(5)), existing = 2, requested = 5)
    assertEquals(SetOutcome.Renumbered(2, 5), outcome)
    assertEquals(listOf(5), markers.map { it.number })
    assertEquals("new", markers.single().text)
  }

  @Test
  fun aNumberAskedForMovesFromAnotherItem() {
    val (outcome, markers) = assign(listOf(marker(5)), existing = null, requested = 5)
    assertEquals(SetOutcome.Moved(5, "item 5"), outcome)
    assertEquals("new", markers.single().text)
    assertEquals(SetOutcome.Placed(7), assign(listOf(marker(5)), existing = null, requested = 7).first)
  }
}
