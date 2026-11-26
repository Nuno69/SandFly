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

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.placemarkers.MarkerResolver.Entry

/**
 * The last spot: where focus was before it jumped to a marker, to go back to. Jumps from one marker
 * to the next keep the spot, so it stays where they started, and going back makes the item focus
 * leaves the spot, to go back to it in turn. Kept only in memory, as it holds the text of an item
 * of another app. [N] is a node of the screen, and [same] tells whether two nodes read at two times
 * show the same item; used on the main thread.
 */
internal class LastSpot<N : Any>(private val same: (N, N) -> Boolean) {
  private class Spot(val entry: Entry, val atMarker: Boolean)

  private var spot: Spot? = null

  /** The item the last jump to a marker moved focus to, while focus may still be on it. */
  private var landed: N? = null

  /** The spot, described as a marker with the number [NUMBER], or null. */
  val entry: Entry?
    get() = spot?.entry

  /**
   * Whether the item with [focus] is to be described for a jump to a marker, or back to the spot
   * when [back], as it becomes the spot unless the jump goes where focus is. A jump to a marker
   * from the item an earlier one moved focus to keeps the spot.
   */
  fun describes(focus: N?, back: Boolean): Boolean = focus != null && (back || !onLanded(focus))

  /**
   * Notes that a jump to a marker, or back to the spot when [back], moved focus from [focus] to
   * [to]. [from] describes [focus], when [describes] asked for it and focus moved, and becomes the
   * spot.
   */
  fun jumped(back: Boolean, focus: N?, from: Entry?, to: N) {
    // Going back to a marker carries on the jumps between markers, which keep the spot.
    val toMarker = !back || spot?.atMarker == true
    if (from != null) {
      spot = Spot(Entry(from.key, from.marker.copy(number = NUMBER)), atMarker = focus != null && onLanded(focus))
    }
    landed = if (toMarker) to else null
  }

  private fun onLanded(focus: N): Boolean = landed?.let { same(focus, it) } == true

  /** Forgets the spot, as when SandFly turns off. */
  fun clear() {
    spot = null
    landed = null
  }

  companion object {
    /** The number of the spot's marker, which no place marker has. */
    const val NUMBER = -1
  }
}

/**
 * Whether [a] and [b], read at two times, are one node showing the same item, as a list gives the
 * node of a row to another item when it scrolls.
 */
internal fun showsSameItem(a: AccessibilityNodeInfoCompat, b: AccessibilityNodeInfoCompat): Boolean =
  a == b &&
    a.text?.toString() == b.text?.toString() &&
    a.contentDescription?.toString() == b.contentDescription?.toString() &&
    a.collectionItemInfo?.rowIndex == b.collectionItemInfo?.rowIndex
