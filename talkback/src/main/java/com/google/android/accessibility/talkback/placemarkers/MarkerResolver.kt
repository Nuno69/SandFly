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

import android.util.LruCache
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.selector.VerticalNavigation
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Role

/** How a marker's item was found, for the log, which never has the item's text. */
internal enum class Tier {
  /** The node found for the marker before, still showing the item. */
  LIVE,
  /** The only node with the item's resource ID. */
  ID,
  /** The node at the item's position under its anchor. */
  EXACT_PATH,
  /** The one node under the anchor that matches the item's path best. */
  PATH,
  /** The one node in the windows with the item's role and text. */
  SCAN,
}

/** What looking for a marker's item found: [node], or nothing, or more than one item. */
internal data class Resolution(val node: AccessibilityNodeInfoCompat?, val tier: Tier?, val ambiguous: Boolean) {
  companion object {
    val NOT_FOUND = Resolution(null, null, ambiguous = false)
    val AMBIGUOUS = Resolution(null, null, ambiguous = true)

    fun found(node: AccessibilityNodeInfoCompat, tier: Tier) = Resolution(node, tier, ambiguous = false)
  }
}

/**
 * The nodes markers were found at in this session, so that going to a marker again needs no search
 * while its item is still shown. Each is checked again before it is used. Copies are kept and
 * given out, so that no thread refreshes a node another one uses.
 */
internal class LiveNodes {
  private val nodes = LruCache<Pair<ScreenKey, Int>, AccessibilityNodeInfoCompat>(MAX_NODES)

  fun get(key: ScreenKey, number: Int): AccessibilityNodeInfoCompat? =
    nodes[key to number]?.let(AccessibilityNodeInfoCompat::obtain)

  fun put(key: ScreenKey, number: Int, node: AccessibilityNodeInfoCompat) {
    nodes.put(key to number, AccessibilityNodeInfoCompat.obtain(node))
  }

  fun remove(key: ScreenKey, number: Int) {
    nodes.remove(key to number)
  }

  fun clear() = nodes.evictAll()

  private companion object {
    const val MAX_NODES = 64
  }
}

/**
 * Finds the items of place markers on the screen. All markers are looked for the quickest ways
 * first, and the whole windows are read once for the ones left, so a marker that isn't there
 * doesn't use up the time of the others. An item is found only when one node matches it; when two
 * could be it, or the search was cut short, it isn't found, so focus never moves to a wrong item.
 * An item in a list, whose rows are reused and can show the same text, also needs the text of a
 * row beside its row, and isn't told by its position on screen.
 */
internal class MarkerResolver(private val live: LiveNodes) {

  /** A saved marker and the screen it was set on. */
  data class Entry(val key: ScreenKey, val marker: StoredMarker)

  /** Looks for the items of [entries] in the windows with [roots], giving a result for each. */
  fun resolveAll(entries: List<Entry>, roots: List<AccessibilityNodeInfoCompat>, deadline: Deadline): List<Resolution> {
    val results = arrayOfNulls<Resolution>(entries.size)
    fun open() = entries.indices.filter { results[it] == null }
    for (i in entries.indices) {
      val (key, marker) = entries[i]
      live.get(key, marker.number)?.let {
        if (isStillShown(marker, it, deadline)) results[i] = Resolution.found(it, Tier.LIVE)
      }
    }
    for (i in open()) {
      val marker = entries[i].marker
      if (marker.viewId == null || marker.inList || deadline.passed()) continue
      val withId =
        roots
          .flatMap { AccessibilityNodeInfoUtils.findNodesByViewId(it, marker.viewId) }
          .filter { classMatches(marker, it) }
      if (withId.size == 1) results[i] = Resolution.found(withId[0], Tier.ID)
    }
    val anchors = Anchors(roots, deadline)
    // First every item at its own position, then the slower search for those that moved.
    for (i in open()) {
      val marker = entries[i].marker
      if (marker.path.isEmpty() || marker.inList || deadline.passed()) continue
      for (anchor in anchors.of(marker)) {
        val node = atPosition(anchor, marker.path) ?: continue
        if (contentMatches(marker, node)) {
          results[i] = Resolution.found(node, Tier.EXACT_PATH)
          break
        }
      }
    }
    for (i in open()) {
      val marker = entries[i].marker
      if (marker.path.isEmpty()) continue
      results[i] = byPath(marker, anchors.of(marker), deadline)
    }
    val scanned = open().filter { !entries[it].marker.inList && entries[it].marker.text != null }
    if (scanned.isNotEmpty()) {
      val found = scan(scanned.map { entries[it].marker }, roots, deadline)
      scanned.forEachIndexed { at, i -> results[i] = found[at] }
    }
    return results.map { it ?: Resolution.NOT_FOUND }
  }

  /**
   * Looks for the items of [markers] among the rows of [list], the list they are in, which has just
   * scrolled, by their paths under the list.
   */
  fun resolveInList(markers: List<StoredMarker>, list: AccessibilityNodeInfoCompat, deadline: Deadline): List<Resolution> =
    markers.map { marker ->
      val place = marker.list
      val below =
        when {
          place == null || place.depth == ListPlace.UNKNOWN -> emptyList()
          place.depth == ListPlace.AT_ANCHOR -> marker.path
          else -> marker.path.drop(place.depth + 1)
        }
      if (below.isEmpty()) Resolution.NOT_FOUND
      else candidates(marker, listOf(list), below, listAt = 0, deadline) ?: Resolution.NOT_FOUND
    }

  /**
   * Returns the list [marker]'s item is in, when it is on the screen with [roots] and scrolls up and
   * down, or null.
   */
  fun listOf(marker: StoredMarker, roots: List<AccessibilityNodeInfoCompat>, deadline: Deadline): AccessibilityNodeInfoCompat? {
    val place = marker.list ?: return null
    val anchors = Anchors(roots, deadline).of(marker)
    val list =
      when (place.depth) {
        ListPlace.UNKNOWN -> null
        ListPlace.AT_ANCHOR -> anchors.singleOrNull()
        else -> {
          val toList = marker.path.take(place.depth + 1)
          anchors.firstNotNullOfOrNull { atPosition(it, toList) }
            ?: run {
              val lists = mutableListOf<AccessibilityNodeInfoCompat>()
              val budget = IntArray(1) { MAX_PATH_NODES }
              val complete =
                anchors.all { descend(Stack(it), toList, deadline, budget) { stack -> lists += stack.node } }
              lists.distinct().singleOrNull()?.takeIf { complete }
            }
        }
      }
    return list?.takeIf { VerticalNavigation.scrollsVertically(it) }
  }

  /** Whether [node], found for [marker] before, still shows its item. */
  private fun isStillShown(marker: StoredMarker, node: AccessibilityNodeInfoCompat, deadline: Deadline): Boolean {
    if (!node.refresh() || (!marker.web && !node.isVisibleToUser) || !contentMatches(marker, node)) {
      return false
    }
    if (!marker.inList || !marker.hasBeside) return true
    val chain = MarkerCapture.ancestors(node, deadline)
    val listAt = MarkerCapture.listIndex(chain) ?: return false
    val index = MarkerCapture.indexIn(chain[listAt], chain[listAt - 1], deadline)
    return besideMatches(marker, chain[listAt], index)
  }

  /**
   * Looks for [marker]'s item by its path under [anchors], away from its own position: null when
   * nothing matches. In a list, a position is only a place on screen, which other rows take as it
   * scrolls, so it isn't looked at.
   */
  private fun byPath(marker: StoredMarker, anchors: List<AccessibilityNodeInfoCompat>, deadline: Deadline): Resolution? {
    val listAt =
      when (val depth = marker.list?.depth) {
        null -> null
        ListPlace.UNKNOWN -> return null
        else -> depth + 1
      }
    return candidates(marker, anchors, marker.path, listAt, deadline)
  }

  /**
   * The one node under [anchors] at the end of [path] with [marker]'s text, scored by its position
   * and the text beside it: null when there is none, ambiguous when two score the same, and not
   * found when the search was cut short. [listAt] is where the list is on the way down, the anchor
   * being 0, for an item in a list.
   */
  private fun candidates(
    marker: StoredMarker,
    anchors: List<AccessibilityNodeInfoCompat>,
    path: List<PathStep>,
    listAt: Int?,
    deadline: Deadline,
  ): Resolution? {
    val scored = mutableListOf<Pair<AccessibilityNodeInfoCompat, Double>>()
    val budget = IntArray(1) { MAX_PATH_NODES }
    val complete =
      anchors.all { anchor ->
        descend(Stack(anchor), path, deadline, budget) { stack ->
          val node = stack.node
          if (!contentMatches(marker, node)) return@descend
          val sideAt = listAt ?: (stack.depth - 1)
          val beside = besideMatches(marker, stack.nodes[sideAt], stack.indexes[sideAt])
          // A row is told from other rows only by the rows beside it, and an item with no text by
          // its position and the item beside it.
          if (listAt != null && !beside) return@descend
          if (marker.text == null && !beside) return@descend
          val position = listAt == null && stack.indexes[stack.depth - 1] == path.last().index
          scored += node to ((if (position) POSITION_SCORE else 0.0) + (if (beside) BESIDE_SCORE else 0.0))
        }
      }
    if (!complete) return Resolution.NOT_FOUND
    return best(scored, Tier.PATH)
  }

  /**
   * Goes down from the top of [stack] along the classes and resource IDs of [path], calling [found]
   * for each node at its end. Returns false when the time or [budget] ran out first.
   */
  private fun descend(
    stack: Stack,
    path: List<PathStep>,
    deadline: Deadline,
    budget: IntArray,
    found: (Stack) -> Unit,
  ): Boolean {
    if (stack.depth == path.size) {
      found(stack)
      return true
    }
    val node = stack.node
    for (i in 0 until node.childCount) {
      if (deadline.passed() || --budget[0] < 0) return false
      val child = node.getChild(i) ?: continue
      if (!stepMatches(path[stack.depth], child)) continue
      stack.push(child, i)
      val complete = descend(stack, path, deadline, budget, found)
      stack.pop()
      if (!complete) return false
    }
    return true
  }

  /** The node reached from [anchor] by the positions of [path], or null when it doesn't match. */
  private fun atPosition(anchor: AccessibilityNodeInfoCompat, path: List<PathStep>): AccessibilityNodeInfoCompat? {
    var current = anchor
    for (step in path) {
      if (step.index < 0 || step.index >= current.childCount) return null
      val child = current.getChild(step.index) ?: return null
      if (!stepMatches(step, child)) return null
      current = child
    }
    return current
  }

  /** The one candidate scored above all others, none, or more than one. */
  private fun best(scored: List<Pair<AccessibilityNodeInfoCompat, Double>>, tier: Tier): Resolution? {
    val distinct = scored.distinctBy { it.first }
    if (distinct.isEmpty()) return null
    val top = distinct.maxOf { it.second }
    val best = distinct.filter { it.second == top }
    return if (best.size == 1) Resolution.found(best[0].first, tier) else Resolution.AMBIGUOUS
  }

  /**
   * Reads the windows once for the items of [markers] by their roles and texts. An item is found
   * only when the whole windows were read and one node has them.
   */
  private fun scan(markers: List<StoredMarker>, roots: List<AccessibilityNodeInfoCompat>, deadline: Deadline): List<Resolution> {
    val byRole = markers.indices.groupBy { markers[it].role }
    val matches = List(markers.size) { mutableListOf<AccessibilityNodeInfoCompat>() }
    val budget = IntArray(1) { MAX_SCAN_NODES }
    val complete =
      roots.all { root ->
        walk(root, budget, deadline) { node ->
          val ofRole = byRole[Role.getRole(node)] ?: return@walk false
          val text = MarkerText.of(node) ?: return@walk false
          for (i in ofRole) {
            if (text == markers[i].text && classMatches(markers[i], node)) matches[i] += node
          }
          false
        }
      }
    return matches.map {
      if (!complete) Resolution.NOT_FOUND
      else best(it.map { node -> node to 0.0 }, Tier.SCAN) ?: Resolution.NOT_FOUND
    }
  }

  /**
   * Visits [root] and the nodes under it, breadth first, until [visit] returns true. Returns false
   * when the time or [budget] ran out first.
   */
  private fun walk(
    root: AccessibilityNodeInfoCompat,
    budget: IntArray,
    deadline: Deadline,
    visit: (AccessibilityNodeInfoCompat) -> Boolean,
  ): Boolean {
    val pending = ArrayDeque<AccessibilityNodeInfoCompat>()
    pending += root
    var queued = 1
    while (pending.isNotEmpty()) {
      if (deadline.passed()) return false
      val node = pending.removeFirst()
      if (visit(node)) return true
      for (i in 0 until node.childCount) {
        // Every node queued is read, so the budget limits the queue too.
        if (++queued > budget[0]) return false
        node.getChild(i)?.let(pending::addLast)
      }
    }
    budget[0] -= queued
    return true
  }

  /** The nodes the paths of markers start under, each found once for all markers. */
  private inner class Anchors(private val roots: List<AccessibilityNodeInfoCompat>, private val deadline: Deadline) {
    private val byId = HashMap<String, List<AccessibilityNodeInfoCompat>>()
    private var documents: List<AccessibilityNodeInfoCompat>? = null

    fun of(marker: StoredMarker): List<AccessibilityNodeInfoCompat> =
      when {
        marker.anchorId != null ->
          byId
            .getOrPut(marker.anchorId) { roots.flatMap { AccessibilityNodeInfoUtils.findNodesByViewId(it, marker.anchorId) } }
            .takeIf { it.size == 1 }
            .orEmpty()
        marker.web -> documents ?: roots.mapNotNull(::firstDocument).also { documents = it }
        else -> roots
      }

    /** The outermost web document in the window with [root]. */
    private fun firstDocument(root: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
      var document: AccessibilityNodeInfoCompat? = null
      walk(root, IntArray(1) { MAX_DOCUMENT_SEARCH }, deadline) { node ->
        AccessibilityNodeInfoUtils.isWebDocument(node).also { if (it) document = node }
      }
      return document
    }
  }

  /** The nodes on the way down from an anchor, with the position of each among its parent's. */
  private class Stack(anchor: AccessibilityNodeInfoCompat) {
    val nodes = mutableListOf(anchor)
    val indexes = mutableListOf<Int>()

    /** How far down from the anchor the top node is. */
    val depth: Int
      get() = indexes.size

    val node: AccessibilityNodeInfoCompat
      get() = nodes.last()

    fun push(node: AccessibilityNodeInfoCompat, index: Int) {
      nodes += node
      indexes += index
    }

    fun pop() {
      nodes.removeAt(nodes.size - 1)
      indexes.removeAt(indexes.size - 1)
    }
  }

  private fun stepMatches(step: PathStep, node: AccessibilityNodeInfoCompat): Boolean =
    step.className == node.className?.toString() &&
      (step.viewId == null || step.viewId == node.viewIdResourceName)

  private fun classMatches(marker: StoredMarker, node: AccessibilityNodeInfoCompat) =
    marker.className == node.className?.toString()

  private fun contentMatches(marker: StoredMarker, node: AccessibilityNodeInfoCompat): Boolean =
    classMatches(marker, node) && MarkerText.of(node) == marker.text

  /**
   * Whether the text of a child of [parent] beside the one at [index] is what was beside [marker]'s
   * item. False when nothing was beside it, as nothing then tells it apart.
   */
  private fun besideMatches(marker: StoredMarker, parent: AccessibilityNodeInfoCompat, index: Int): Boolean {
    if (index < 0 || !marker.hasBeside) return false
    val (before, after) = MarkerCapture.besideTexts(parent, index)
    return (marker.before != null && marker.before == before) ||
      (marker.after != null && marker.after == after)
  }

  private companion object {
    const val POSITION_SCORE = 1.1
    const val BESIDE_SCORE = 1.0
    const val MAX_PATH_NODES = 3_000
    const val MAX_SCAN_NODES = 5_000
    const val MAX_DOCUMENT_SEARCH = 2_000
  }
}
