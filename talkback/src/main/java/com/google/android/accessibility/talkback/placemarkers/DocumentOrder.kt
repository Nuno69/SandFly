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

/**
 * The order of nodes under [tops], such as the roots of windows: the top a node is under, then the
 * position of each of its ancestors among its siblings, down to the node. Unlike the reading order,
 * it needs only the node's ancestors and their siblings, not the whole screen, so the next marker
 * is found without reading every item. An item comes before the items in it.
 */
internal class DocumentOrder(private val tops: List<AccessibilityNodeInfoCompat>) {

  /** Returns where [node] is in the order, or null when it isn't under one of the tops. */
  fun positionOf(node: AccessibilityNodeInfoCompat, deadline: Deadline): IntArray? {
    val chain = MarkerCapture.ancestors(node, deadline)
    val topAt = chain.indices.firstOrNull { chain[it] in tops } ?: return null
    val position = IntArray(topAt + 1)
    position[0] = tops.indexOf(chain[topAt])
    for (i in topAt - 1 downTo 0) {
      val index = MarkerCapture.indexIn(chain[i + 1], chain[i], deadline)
      if (index < 0) return null
      position[topAt - i] = index
    }
    return position
  }

  /**
   * Returns the one of [items] nearest after [from], or before it when not [forward], each at the
   * node [nodeOf] gives; the first or last when [from] is null.
   */
  fun <T> nearest(
    items: List<T>,
    from: AccessibilityNodeInfoCompat?,
    forward: Boolean,
    deadline: Deadline,
    nodeOf: (T) -> AccessibilityNodeInfoCompat,
  ): T? {
    val start = from?.let { positionOf(it, deadline) }
    val placed = items.mapNotNull { item -> positionOf(nodeOf(item), deadline)?.let { item to it } }
    val ahead =
      placed.filter { (_, position) ->
        start == null || compare(position, start).let { if (forward) it > 0 else it < 0 }
      }
    val order = Comparator<Pair<T, IntArray>> { a, b -> compare(a.second, b.second) }
    return (if (forward) ahead.minWithOrNull(order) else ahead.maxWithOrNull(order))?.first
  }

  companion object {
    /** Compares positions: a node before another, or before the nodes in it. */
    fun compare(a: IntArray, b: IntArray): Int {
      for (i in 0 until minOf(a.size, b.size)) {
        if (a[i] != b[i]) return a[i].compareTo(b[i])
      }
      return a.size.compareTo(b.size)
    }
  }
}
