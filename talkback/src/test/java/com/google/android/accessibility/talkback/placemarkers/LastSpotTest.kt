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

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import com.google.android.accessibility.talkback.placemarkers.MarkerResolver.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastSpotTest {
  private val key = ScreenKey("com.example", "com.example.Chat", null)
  private val spot = LastSpot<String> { a, b -> a == b }

  private fun entry(text: String) =
    Entry(key, StoredMarker(0, 0, "android.widget.TextView", 0, text, null, null, false, emptyList(), null, null, null))

  /** A jump the way the navigator makes it: [from] is described only when asked for. */
  private fun jump(from: String?, to: String, back: Boolean = false) {
    val described = if (spot.describes(from, back) && from != to) entry(from!!) else null
    spot.jumped(back, from, described, to)
  }

  private val spotText: String?
    get() = spot.entry?.marker?.text

  @Test
  fun aJumpToAMarkerLeavesWhereFocusWasAsTheSpot() {
    jump(from = "message", to = "field")
    assertEquals("message", spotText)
    assertEquals(LastSpot.NUMBER, spot.entry?.marker?.number)
  }

  @Test
  fun jumpsFromMarkerToMarkerKeepTheSpot() {
    jump(from = "message", to = "marker 1")
    assertFalse(spot.describes("marker 1", back = false))
    jump(from = "marker 1", to = "marker 2")
    jump(from = "marker 2", to = "marker 3")
    assertEquals("message", spotText)
  }

  @Test
  fun aJumpFromAnotherItemMovesTheSpot() {
    jump(from = "message", to = "marker 1")
    jump(from = "next message", to = "marker 2")
    assertEquals("next message", spotText)
  }

  @Test
  fun goingBackTogglesBetweenTheSpotAndTheMarker() {
    jump(from = "message", to = "marker 1")
    jump(from = "marker 1", to = "marker 2")
    jump(from = "marker 2", to = "message", back = true)
    assertEquals("marker 2", spotText)
    jump(from = "message", to = "marker 2", back = true)
    assertEquals("message", spotText)
  }

  @Test
  fun markerJumpsAfterGoingBackToAMarkerKeepTheSpot() {
    jump(from = "message", to = "marker 1")
    jump(from = "marker 1", to = "message", back = true)
    jump(from = "message", to = "marker 1", back = true)
    jump(from = "marker 1", to = "marker 2")
    assertEquals("message", spotText)
  }

  @Test
  fun aMarkerJumpFromTheSpotGoneBackToMovesTheSpotThere() {
    jump(from = "message", to = "marker 1")
    jump(from = "marker 1", to = "message", back = true)
    assertTrue(spot.describes("message", back = false))
    jump(from = "message", to = "marker 2")
    assertEquals("message", spotText)
    jump(from = "marker 2", to = "message", back = true)
    assertEquals("marker 2", spotText)
  }

  @Test
  fun nothingIsRecordedWithoutFocusOrWhenFocusIsAlreadyThere() {
    jump(from = null, to = "marker 1")
    assertNull(spot.entry)
    jump(from = "marker 2", to = "marker 2")
    assertNull(spot.entry)
  }

  /** The node of the list row [view], read as showing [text] at [row]. */
  @Suppress("DEPRECATION")
  private fun row(view: View, text: String, row: Int) =
    AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain(view)).apply {
      this.text = text
      setCollectionItemInfo(CollectionItemInfoCompat.obtain(row, 1, 0, 1, false))
    }

  @Test
  fun aRowShowingAnotherItemIsNotTheMarkerFocusJumpedTo() {
    val context = RuntimeEnvironment.getApplication()
    val reading = View(context)
    val marked = View(context)
    val rows = LastSpot(::showsSameItem)
    rows.jumped(back = false, focus = row(reading, "message", 3), from = entry("message"), to = row(marked, "marked message", 7))
    assertFalse(rows.describes(row(marked, "marked message", 7), back = false))
    // The marked row's view, given to other items as the list scrolls.
    assertTrue(rows.describes(row(marked, "another message", 7), back = false))
    assertTrue(rows.describes(row(marked, "marked message", 9), back = false))
    assertTrue(rows.describes(row(reading, "marked message", 7), back = false))
  }

  @Test
  fun clearingForgetsTheSpot() {
    jump(from = "message", to = "marker 1")
    spot.clear()
    assertNull(spot.entry)
    assertTrue(spot.describes("marker 1", back = false))
  }
}
