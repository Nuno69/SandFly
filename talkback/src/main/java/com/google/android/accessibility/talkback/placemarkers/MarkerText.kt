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
 * The text by which a marker knows its item, read the same way when the marker is set and when its
 * item is looked for. A password field has none, and an edit field only its hint or description,
 * never what was typed in it.
 */
object MarkerText {

  /** The most nodes under an item read for a text of its own. */
  private const val MAX_DESCENDANTS = 5

  /** Returns the text of [node], cut to [max] characters, or null when it has none. */
  fun of(node: AccessibilityNodeInfoCompat, max: Int = PlaceMarkerStore.MAX_TEXT): String? {
    if (node.isPassword) return null
    val text =
      if (node.isEditable) firstOf(node.hintText, node.contentDescription)
      else firstOf(node.contentDescription, node.text) ?: descendantText(node)
    return text?.toString()?.trim()?.take(max)?.ifEmpty { null }
  }

  /** Returns the shorter text of the child of [parent] at [index], as beside a marked item. */
  fun ofChild(parent: AccessibilityNodeInfoCompat, index: Int): String? =
    if (index < 0 || index >= parent.childCount) null
    else parent.getChild(index)?.let { of(it, PlaceMarkerStore.MAX_SIDE_TEXT) }

  /**
   * The first text of the nodes under [node], depth first, reading at most [MAX_DESCENDANTS]. Items
   * off screen are read too, as a web page keeps them in its tree.
   */
  private fun descendantText(node: AccessibilityNodeInfoCompat): CharSequence? {
    val pending = ArrayDeque<AccessibilityNodeInfoCompat>()
    addChildren(node, pending, MAX_DESCENDANTS)
    var read = 0
    while (pending.isNotEmpty() && read < MAX_DESCENDANTS) {
      val child = pending.removeFirst()
      read++
      if (child.isPassword || child.isEditable) continue
      firstOf(child.contentDescription, child.text)?.let {
        return it
      }
      addChildren(child, pending, MAX_DESCENDANTS - read)
    }
    return null
  }

  /** Puts the first of [node]'s children, at most [max], before the [pending] nodes. */
  private fun addChildren(node: AccessibilityNodeInfoCompat, pending: ArrayDeque<AccessibilityNodeInfoCompat>, max: Int) {
    for (i in minOf(node.childCount, max) - 1 downTo 0) node.getChild(i)?.let(pending::addFirst)
  }

  private fun firstOf(vararg texts: CharSequence?): CharSequence? =
    texts.firstOrNull { !it.isNullOrBlank() }
}
