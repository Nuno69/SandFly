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

import android.os.SystemClock

/**
 * One node on the way from a marker's anchor down to its item: its class, its resource ID, and its
 * position among its parent's children, or -1 when that wasn't read.
 */
data class PathStep(val className: String?, val viewId: String?, val index: Int)

/**
 * Where a marked item is in a list, whose rows are reused and can show the same text: the list is
 * the node at [depth] in the marker's path, its anchor when [depth] is [AT_ANCHOR], or not known
 * when it is [UNKNOWN]. [row] is the item's row and [rowCount] the list's rows when the item was
 * last found, or -1 when the list doesn't number them.
 */
data class ListPlace(val depth: Int, val row: Int, val rowCount: Int) {
  companion object {
    const val AT_ANCHOR = -1
    const val UNKNOWN = -2
  }
}

/**
 * A place marker: the item it marks, described so that it can be found again after the app or the
 * device restarts.
 *
 * The item is found under its anchor: the node with the resource ID [anchorId], the outermost web
 * document when [web], or else the window's root. [path] leads from the anchor's child down to the
 * item. [text] is the item's text as [MarkerText] reads it, never what was typed in a field, and
 * [before] and [after] are those of the items beside it, or of the rows beside its row in a list.
 * [viewId] is the item's resource ID when no other item had it.
 */
data class StoredMarker(
  val number: Int,
  val lastUsed: Long,
  val className: String?,
  val role: Int,
  val text: String?,
  val viewId: String?,
  val anchorId: String?,
  val web: Boolean,
  val path: List<PathStep>,
  val before: String?,
  val after: String?,
  val list: ListPlace?,
) {
  /** Whether the item is in a list, so that its text alone doesn't tell it from other rows. */
  val inList: Boolean
    get() = list != null

  /** Whether the text of the items or rows beside the item is known, to tell it apart. */
  val hasBeside: Boolean
    get() = before != null || after != null
}

/**
 * The markers of one screen. [title] is the title of its window, or of its page in a browser, when
 * a marker was last set on it, to name the screen in the list of markers, or null when it had none.
 */
data class StoredScreen(
  val key: ScreenKey,
  val lastUsed: Long,
  val markers: List<StoredMarker>,
  val title: String? = null,
)

/** What setting a marker did, to be said; [number] is the marker the item has now. */
sealed interface SetOutcome {
  val number: Int

  /** The item got the free marker [number]. */
  data class Placed(override val number: Int) : SetOutcome

  /** The item already has the marker [number]. */
  data class AlreadyMarked(override val number: Int) : SetOutcome

  /** The item's marker [from] is now the marker [number]. */
  data class Renumbered(val from: Int, override val number: Int) : SetOutcome

  /** The marker [number] moved to the item from another, whose text was [oldText]. */
  data class Moved(override val number: Int, val oldText: String?) : SetOutcome
}

/** Picks the number of a marker that is set, and what it does to the markers already set. */
object MarkerNumbers {

  /**
   * Returns what setting a marker on an item does to the screen's [markers]. [existing] is the
   * number of the marker the item has, if any, and [requested] the number asked for, or null for
   * the first free number, or else the one used longest ago. [marker] makes the new marker with a
   * number.
   */
  fun assign(
    markers: List<StoredMarker>,
    existing: Int?,
    requested: Int?,
    now: Long,
    marker: (Int) -> StoredMarker,
  ): Pair<SetOutcome, List<StoredMarker>> {
    val byNumber = markers.associateBy { it.number }
    val current = existing?.let(byNumber::get)
    if (current != null && (requested == null || requested == existing)) {
      return SetOutcome.AlreadyMarked(current.number) to
        markers.map { if (it === current) it.copy(lastUsed = now) else it }
    }
    if (current != null && requested != null) {
      val kept = markers.filter { it !== current && it.number != requested }
      return SetOutcome.Renumbered(current.number, requested) to sorted(kept + marker(requested))
    }
    val number =
      requested
        ?: (1..PlaceMarkerCommands.COUNT).firstOrNull { it !in byNumber }
        ?: markers.minWith(compareBy<StoredMarker> { it.lastUsed }.thenBy { it.number }).number
    val replaced = byNumber[number]
    val kept = markers.filter { it.number != number }
    val outcome =
      if (replaced == null) SetOutcome.Placed(number) else SetOutcome.Moved(number, replaced.text)
    return outcome to sorted(kept + marker(number))
  }

  private fun sorted(markers: List<StoredMarker>) = markers.sortedBy { it.number }
}

/** A time after which a search of the screen stops, so that a command never hangs. */
internal class Deadline(millis: Long) {
  private val end = SystemClock.uptimeMillis() + millis

  fun passed(): Boolean = SystemClock.uptimeMillis() >= end
}
