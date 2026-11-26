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
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Role

/** Describes the focused item as a place marker, and reads the parts of the screen markers use. */
internal object MarkerCapture {
  /** The most ancestors of an item read. */
  private const val MAX_ANCESTORS = 64

  /** The most ancestors looked at for an anchor with a resource ID of its own. */
  private const val MAX_ANCHOR_CHECKS = 8

  /** The most children of a node looked through for the position of one of them. */
  private const val MAX_CHILDREN = 500

  /**
   * Returns a maker of the marker of [node] with a number and a time of use. Reads up to
   * [deadline]; what isn't read by then, such as positions, is left out, and the marker is found by
   * what was read.
   */
  fun capture(node: AccessibilityNodeInfoCompat, deadline: Deadline): (Int, Long) -> StoredMarker {
    val chain = ancestors(node, deadline)
    // Without the root, a path from the topmost ancestor read wouldn't be found again.
    val root = chain.last()
    val complete = AccessibilityNodeInfoUtils.getRoot(root) == root
    val webRoot = (chain.size - 1 downTo 0).firstOrNull { AccessibilityNodeInfoUtils.isWebDocument(chain[it]) }
    // Lists in a web page aren't reused as the user scrolls, so only an app's list counts.
    val listAt = listIndex(chain, from = (webRoot ?: 0) + 1)
    val idAnchorAt =
      if (webRoot == null && complete) idAnchor(chain, from = listAt ?: 1, root, deadline) else null
    val anchorAt = webRoot ?: idAnchorAt ?: (chain.size - 1)
    val anchored = webRoot != null || idAnchorAt != null || complete
    val indexes = Indexes(chain, deadline)
    val path =
      if (!anchored || anchorAt > PlaceMarkerStore.MAX_PATH) {
        emptyList()
      } else {
        (anchorAt - 1 downTo 0).map {
          PathStep(name(chain[it].className), name(chain[it].viewIdResourceName), indexes[it])
        }
      }
    val list = listAt?.let { listPlace(chain, it, anchorAt, path.isNotEmpty()) }
    // Beside an item of a list are the rows beside its row; beside any other item, its siblings.
    val sideAt = if (listAt != null) listAt - 1 else 0
    val (before, after) =
      chain.getOrNull(sideAt + 1)?.let { besideTexts(it, indexes[sideAt]) } ?: (null to null)
    val viewId = if (listAt == null && complete) uniqueId(chain, 0, root) else null
    val anchorId = idAnchorAt?.let { name(chain[it].viewIdResourceName) }
    val className = name(node.className)
    val role = Role.getRole(node)
    val text = MarkerText.of(node)
    val web = webRoot != null
    return { number, now ->
      StoredMarker(number, now, className, role, text, viewId, anchorId, web, path, before, after, list)
    }
  }

  /** [node] and its ancestors, nearest first; on Android 13 and later one request reads them all. */
  fun ancestors(node: AccessibilityNodeInfoCompat, deadline: Deadline): List<AccessibilityNodeInfoCompat> {
    val chain = mutableListOf(node)
    var current = node
    while (chain.size < MAX_ANCESTORS && !deadline.passed()) {
      val parent =
        current.getParent(
          AccessibilityNodeInfoCompat.FLAG_PREFETCH_ANCESTORS or
            AccessibilityNodeInfoCompat.FLAG_PREFETCH_UNINTERRUPTIBLE
        ) ?: current.parent ?: break
      if (parent in chain) break
      chain += parent
      current = parent
    }
    return chain
  }

  /**
   * The position in [chain] of the nearest list from [from]: a node with rows, or the parent of a
   * row. Whether the list scrolls doesn't matter: a short chat that doesn't scroll reuses its rows
   * once it does.
   */
  fun listIndex(chain: List<AccessibilityNodeInfoCompat>, from: Int = 1): Int? =
    (maxOf(from, 1) until chain.size).firstOrNull {
      chain[it].collectionInfo != null || chain[it - 1].collectionItemInfo != null
    }

  /** The row of the item in the list at [listAt] of [chain], or -1 when the list has no numbers. */
  fun rowOf(chain: List<AccessibilityNodeInfoCompat>, listAt: Int): Int =
    (listAt - 1 downTo 0).firstNotNullOfOrNull { chain[it].collectionItemInfo?.rowIndex } ?: -1

  /** The texts of the children of [parent] before and after the one at [index]. */
  fun besideTexts(parent: AccessibilityNodeInfoCompat, index: Int): Pair<String?, String?> =
    if (index < 0) null to null
    else MarkerText.ofChild(parent, index - 1) to MarkerText.ofChild(parent, index + 1)

  /** The position of [child] among the children of [parent], or -1 when it wasn't found in time. */
  fun indexIn(parent: AccessibilityNodeInfoCompat, child: AccessibilityNodeInfoCompat, deadline: Deadline): Int {
    val count = minOf(parent.childCount, MAX_CHILDREN)
    for (i in 0 until count) {
      if (deadline.passed()) return -1
      if (parent.getChild(i) == child) return i
    }
    return -1
  }

  private fun listPlace(
    chain: List<AccessibilityNodeInfoCompat>,
    listAt: Int,
    anchorAt: Int,
    hasPath: Boolean,
  ): ListPlace {
    val depth =
      when {
        listAt == anchorAt -> ListPlace.AT_ANCHOR
        listAt < anchorAt && hasPath -> anchorAt - 1 - listAt
        else -> ListPlace.UNKNOWN
      }
    val row = rowOf(chain, listAt)
    val rowCount = chain[listAt].collectionInfo?.rowCount ?: -1
    return if (row >= 0 && rowCount > 0) ListPlace(depth, row, rowCount) else ListPlace(depth, -1, -1)
  }

  /** The nearest of the ancestors from [from] with a resource ID no other item has, or null. */
  private fun idAnchor(
    chain: List<AccessibilityNodeInfoCompat>,
    from: Int,
    root: AccessibilityNodeInfoCompat,
    deadline: Deadline,
  ): Int? {
    var checked = 0
    for (i in from until chain.size - 1) {
      if (deadline.passed() || checked >= MAX_ANCHOR_CHECKS) return null
      if (chain[i].viewIdResourceName == null) continue
      checked++
      if (uniqueId(chain, i, root) != null) return i
    }
    return null
  }

  /**
   * The resource ID of the node at [at] in [chain] when it names only that node in the window: it
   * isn't one of Android's, the node isn't a row of a list or in one, whose rows share resource
   * IDs, and asking the app for the ID finds just that node.
   */
  private fun uniqueId(chain: List<AccessibilityNodeInfoCompat>, at: Int, root: AccessibilityNodeInfoCompat): String? {
    val id = chain[at].viewIdResourceName ?: return null
    if (id.startsWith("android:")) return null
    if ((at until chain.size).any { chain[it].collectionItemInfo != null }) return null
    val found = AccessibilityNodeInfoUtils.findNodesByViewId(root, id)
    return if (found.size == 1 && found[0] == chain[at]) name(id) else null
  }

  /** The positions of the nodes of a chain among their parents' children, each read once. */
  private class Indexes(private val chain: List<AccessibilityNodeInfoCompat>, private val deadline: Deadline) {
    private val read = IntArray(chain.size) { UNREAD }

    operator fun get(at: Int): Int {
      if (at + 1 >= chain.size) return -1
      if (read[at] == UNREAD) read[at] = indexIn(chain[at + 1], chain[at], deadline)
      return read[at]
    }

    private companion object {
      const val UNREAD = -2
    }
  }

  private fun name(text: CharSequence?): String? =
    text?.toString()?.take(PlaceMarkerStore.MAX_NAME)?.ifEmpty { null }
}
