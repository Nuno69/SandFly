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

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.placemarkers.MarkerResolver.Entry
import com.google.android.accessibility.talkback.placemarkers.PlaceMarkerCommands.Command
import com.google.android.accessibility.talkback.scripting.Scripts
import com.google.android.accessibility.talkback.selector.ItemFocuser
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils
import com.google.android.accessibility.utils.NodeQueryExecutor
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.WebInterfaceUtils
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.RejectedExecutionException

/**
 * Runs the place marker commands and the Place markers reading control. The screen is read on a
 * background thread, within a time limit; focus, speech and scrolling happen on the main thread.
 * Only the way an item was found and how long it took are logged, never its text.
 */
class PlaceMarkerNavigator(
  private val service: AccessibilityService,
  private val pipeline: Pipeline.FeedbackReturner,
  private val focusMonitor: AccessibilityFocusMonitor,
) {
  private val main = Handler(Looper.getMainLooper())
  private val executor = NodeQueryExecutor("PlaceMarkers", /* threads= */ 1, /* capacity= */ 4)
  private val live = LiveNodes()
  private val resolver = MarkerResolver(live)
  private val focuser = ItemFocuser(pipeline)
  private val scroller = MarkerScroller(pipeline, ::inBackground)
  private val flush = Runnable { PlaceMarkerStore.flush(service) }
  private val lastSpot = LastSpot(::showsSameItem)

  /** The number of the last command, so that the result of one replaced by another is dropped. */
  private var request = 0

  /** The item focus was on when the last command ran, to become the last spot. */
  private var commandFocus: AccessibilityNodeInfoCompat? = null

  /** Runs [command], saying what it did. */
  fun run(eventId: EventId?, command: Command) {
    cancelPending()
    active = this
    if (ScreenMonitor.isDeviceLocked(service)) {
      say(eventId, service.getString(R.string.place_markers_locked), failed = true)
      return
    }
    val focus = focusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false)
    commandFocus = focus
    // Read here, as a jump changes the last spot on the main thread when it ends.
    val describe = lastSpot.describes(focus, back = command == PlaceMarkerCommands.LastSpot)
    val spot = lastSpot.entry
    val needsFocus =
      command is PlaceMarkerCommands.SetNumber ||
        command == PlaceMarkerCommands.SetAny ||
        command == PlaceMarkerCommands.Delete
    if (focus == null && needsFocus) {
      say(eventId, service.getString(R.string.touch_hold_no_focus), failed = true)
      return
    }
    val packageName =
      focus?.packageName?.toString() ?: AccessibilityServiceCompatUtils.getActiveWindowPackageName(service)
    if (packageName == null) {
      if (command == PlaceMarkerCommands.Manage) PlaceMarkersFragment.open(service, null) else end(eventId)
      return
    }
    val activity = Scripts.activityOf(packageName)
    // The background thread works on its own copy of the focused node.
    val focused = focus?.let(AccessibilityNodeInfoCompat::obtain)
    val id = ++request
    val started = SystemClock.uptimeMillis()
    executor.start()
    val sent = inBackground {
      val outcome =
        try {
          work(command, focused, packageName, activity, describe, spot)
        } catch (t: Throwable) {
          LogUtils.e(TAG, "Place marker command failed: %s", t.javaClass.simpleName)
          // The list opens even when the screen it was opened from can't be told.
          if (command == PlaceMarkerCommands.Manage) Outcome.Manage(null) else Outcome.End
        }
      main.post {
        if (id == request) {
          LogUtils.d(TAG, "%s in %d ms", outcome.javaClass.simpleName, SystemClock.uptimeMillis() - started)
          apply(eventId, outcome)
        }
      }
    }
    if (!sent) {
      if (command == PlaceMarkerCommands.Manage) PlaceMarkersFragment.open(service, null) else end(eventId)
    }
  }

  /** Moves to the next or previous marker, for the Place markers reading control. */
  fun move(eventId: EventId?, forward: Boolean) =
    run(eventId, if (forward) PlaceMarkerCommands.Next else PlaceMarkerCommands.Previous)

  /** Stops a command still reading the screen or scrolling, such as when the user touches it. */
  fun cancelPending() {
    request++
    scroller.cancel()
    focuser.cancel()
  }

  /** Stops everything and writes the markers' uses, as when SandFly turns off. */
  fun shutdown() {
    cancelPending()
    main.removeCallbacks(flush)
    PlaceMarkerStore.flush(service)
    live.clear()
    lastSpot.clear()
    commandFocus = null
    // The next command starts the reads again.
    executor.shutdown()
    if (active === this) active = null
  }

  private fun inBackground(work: Runnable): Boolean =
    try {
      executor.execute(work)
      true
    } catch (e: RejectedExecutionException) {
      false
    }

  /**
   * Runs [command] on the background thread. [describe] tells whether a jump describes [focus], to
   * become the last spot, and [spot] is the last spot.
   */
  private fun work(
    command: Command,
    focus: AccessibilityNodeInfoCompat?,
    packageName: String,
    activity: String?,
    describe: Boolean,
    spot: Entry?,
  ): Outcome {
    val deadline = Deadline(SEARCH_MS)
    val windows = appWindows(packageName)
    val roots = windows.map { it.root }
    val browser = WebInterfaceUtils.isBrowser(packageName)
    val url = if (browser) roots.firstNotNullOfOrNull(WebInterfaceUtils::currentUrl) else null
    val key = ScreenKey.of(packageName, activity, url)
    // Markers are set only where they are found again: a known activity, and a known page in a
    // browser, where each page has its own.
    val settable = key.activity != null && (key.page != null || !browser)
    val unknown = Outcome.Say(service.getString(R.string.place_marker_screen_unknown), failed = true)
    return when (command) {
      is PlaceMarkerCommands.GoTo -> goTo(command.number, key, focus, roots, deadline, describe)
      PlaceMarkerCommands.Next -> move(forward = true, key, focus, roots, deadline, describe)
      PlaceMarkerCommands.Previous -> move(forward = false, key, focus, roots, deadline, describe)
      PlaceMarkerCommands.LastSpot -> goBack(spot, key, focus, roots, deadline, describe)
      PlaceMarkerCommands.SetAny -> if (settable) set(null, key, focus!!, windows, browser, deadline) else unknown
      is PlaceMarkerCommands.SetNumber ->
        if (settable) set(command.number, key, focus!!, windows, browser, deadline) else unknown
      PlaceMarkerCommands.Delete -> if (settable) delete(key, focus!!, roots, deadline) else unknown
      PlaceMarkerCommands.DeleteAll -> if (settable) deleteAll(key) else unknown
      PlaceMarkerCommands.Manage -> Outcome.Manage(key)
    }
  }

  private fun goTo(
    number: Int,
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat?,
    roots: List<AccessibilityNodeInfoCompat>,
    deadline: Deadline,
    describe: Boolean,
  ): Outcome {
    val entries = entriesShownAt(key).filter { it.marker.number == number }
    if (entries.isEmpty()) {
      return Outcome.Say(service.getString(R.string.place_marker_none_numbered, number), failed = true)
    }
    val notFound = Outcome.Say(service.getString(R.string.place_marker_not_found, number), failed = true)
    return reach(entries, notFound, key, focus, roots, deadline, describe)
  }

  /** Goes back to the last [spot], when it was on this screen. */
  private fun goBack(
    spot: Entry?,
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat?,
    roots: List<AccessibilityNodeInfoCompat>,
    deadline: Deadline,
    describe: Boolean,
  ): Outcome {
    if (spot == null || !key.mayShow(spot.key)) {
      return Outcome.Say(service.getString(R.string.place_marker_no_last_spot), failed = true)
    }
    val notFound = Outcome.Say(service.getString(R.string.place_marker_last_spot_not_found), failed = true)
    return reach(listOf(spot), notFound, key, focus, roots, deadline, describe)
  }

  /**
   * Moves to the item of the one of [entries] found on the screen, or scrolls its list for it, or
   * else gives [notFound], as when more than one is found.
   */
  private fun reach(
    entries: List<Entry>,
    notFound: Outcome,
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat?,
    roots: List<AccessibilityNodeInfoCompat>,
    deadline: Deadline,
    describe: Boolean,
  ): Outcome {
    val resolutions = resolver.resolveAll(entries, roots, deadline)
    if (resolutions.any { it.ambiguous }) return notFound
    val found = entries.indices.filter { resolutions[it].node != null }.distinctBy { resolutions[it].node }
    if (found.size > 1) return notFound
    found.singleOrNull()?.let {
      LogUtils.d(TAG, "Marker found by %s", resolutions[it].tier)
      return focusOutcome(entries[it], resolutions[it].node!!, key, focus, describe)
    }
    val entry = entries.singleOrNull() ?: return notFound
    // With time of its own, as the search above may have used it all.
    val list = resolver.listOf(entry.marker, roots, Deadline(SEARCH_MS)) ?: return notFound
    val plan = planToFind(entry.marker, list) ?: return notFound
    val origin = origin(key, focus)
    return Outcome.ScrollFor(listOf(entry), list, plan, forward = true, notFound, origin, origin.takeIf { describe })
  }

  /**
   * Focuses [node], the item of [entry]. Describes [focus], to become the last spot, when
   * [describe] asks for it and focus moves: before it moves, as moving can scroll the list it is in
   * and give its row to another item.
   */
  private fun focusOutcome(
    entry: Entry,
    node: AccessibilityNodeInfoCompat,
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat?,
    describe: Boolean,
  ): Outcome =
    Outcome.FocusOn(entry, node, if (describe && node != focus) origin(key, focus, SPOT_CAPTURE_MS) else null)

  private fun move(
    forward: Boolean,
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat?,
    roots: List<AccessibilityNodeInfoCompat>,
    deadline: Deadline,
    describe: Boolean,
  ): Outcome {
    val entries = entriesShownAt(key)
    if (entries.isEmpty()) {
      return Outcome.Say(service.getString(R.string.place_markers_none), failed = true)
    }
    val found = found(entries, roots, deadline)
    DocumentOrder(roots).nearest(found, focus, forward, deadline) { it.node }?.let {
      return focusOutcome(it.entry, it.node, key, focus, describe)
    }
    val shown = found.map { it.entry }.toSet()
    scrollToMarkerBeyond(forward, entries.filter { it !in shown }, focus, key, roots, describe)?.let {
      return it
    }
    return if (found.isEmpty()) {
      Outcome.Say(service.getString(R.string.place_markers_not_found), failed = true)
    } else {
      Outcome.End
    }
  }

  /**
   * Scrolls to the nearest of the [unfound] markers that lie further down, or up when not
   * [forward], in a list on screen, by the rows they were found at. A marker of a list with no row
   * numbers isn't scrolled to, as nothing tells which way it is.
   */
  private fun scrollToMarkerBeyond(
    forward: Boolean,
    unfound: List<Entry>,
    focus: AccessibilityNodeInfoCompat?,
    key: ScreenKey,
    roots: List<AccessibilityNodeInfoCompat>,
    describe: Boolean,
  ): Outcome? {
    // With time of its own, as the search for the markers on screen may have used it all.
    val deadline = Deadline(SEARCH_MS)
    class Beyond(val entry: Entry, val list: AccessibilityNodeInfoCompat, val rows: List<Int>, val distance: Int)
    val beyond =
      unfound.mapNotNull { entry ->
        val row = entry.marker.list?.row ?: -1
        if (row < 0 || deadline.passed()) return@mapNotNull null
        val list = resolver.listOf(entry.marker, roots, deadline) ?: return@mapNotNull null
        val rows = shownRows(list)
        if (rows.isEmpty()) return@mapNotNull null
        // Rows can be numbered from the bottom, as in a chat.
        val downward = rows.size < 2 || rows.first() < rows.last()
        val distance = if (forward == downward) row - rows.max() else rows.min() - row
        if (distance > 0) Beyond(entry, list, rows, distance) else null
      }
    val nearest = beyond.minByOrNull { it.distance } ?: return null
    val sameList = beyond.filter { it.list == nearest.list }.map { it.entry }
    val jumps = jumpRows(nearest.entry.marker, nearest.list)
    // Without jumps, as many pages as the rows in between take, and a little more.
    val pages = if (jumps.isEmpty()) nearest.distance / nearest.rows.size + EXTRA_PAGES else EXTRA_PAGES
    val plan = MarkerScroller.Plan(jumps, listOf(MarkerScroller.Step(forward, pages)), restoreRows(nearest.rows))
    val origin = origin(key, focus)
    return Outcome.ScrollFor(sameList, nearest.list, plan, forward, Outcome.End, origin, origin.takeIf { describe })
  }

  private fun set(
    requested: Int?,
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat,
    windows: List<AppWindow>,
    browser: Boolean,
    deadline: Deadline,
  ): Outcome {
    val now = System.currentTimeMillis()
    val markers = PlaceMarkerStore.screen(service, key)?.markers.orEmpty()
    val existing = ownerOf(focus, key, windows.map { it.root }, deadline)?.marker?.number
    // Read after the search above, which has the command's time.
    val title = titleOf(windows, focus, browser)
    val capture = MarkerCapture.capture(focus, Deadline(CAPTURE_MS))
    val (outcome, updated) =
      MarkerNumbers.assign(markers, existing, requested, now) { number -> capture(number, now) }
    PlaceMarkerStore.put(service, key, updated, now, title)
    if (outcome is SetOutcome.Renumbered) live.remove(key, outcome.from)
    live.put(key, outcome.number, focus)
    val text =
      when (outcome) {
        is SetOutcome.Placed -> service.getString(R.string.place_marker_set, outcome.number)
        is SetOutcome.AlreadyMarked -> service.getString(R.string.place_marker_number, outcome.number)
        is SetOutcome.Renumbered ->
          service.getString(R.string.place_marker_renumbered, outcome.from, outcome.number)
        is SetOutcome.Moved ->
          if (outcome.oldText == null) service.getString(R.string.place_marker_moved_here, outcome.number)
          else service.getString(R.string.place_marker_moved_here_from, outcome.number, outcome.oldText.take(OLD_TEXT_SPOKEN))
      }
    return Outcome.Say(text, failed = false)
  }

  private fun delete(
    key: ScreenKey,
    focus: AccessibilityNodeInfoCompat,
    roots: List<AccessibilityNodeInfoCompat>,
    deadline: Deadline,
  ): Outcome {
    val owner =
      ownerOf(focus, key, roots, deadline)
        ?: return Outcome.Say(service.getString(R.string.place_marker_none_here), failed = true)
    PlaceMarkerStore.remove(service, key, owner.marker.number)
    live.remove(key, owner.marker.number)
    return Outcome.Say(service.getString(R.string.place_marker_deleted, owner.marker.number), failed = false)
  }

  private fun deleteAll(key: ScreenKey): Outcome {
    val screen =
      PlaceMarkerStore.screen(service, key)
        ?: return Outcome.Say(service.getString(R.string.place_markers_none), failed = true)
    val count = PlaceMarkerStore.removeScreen(service, key)
    screen.markers.forEach { live.remove(key, it.number) }
    return Outcome.Say(service.resources.getQuantityString(R.plurals.place_markers_deleted, count, count), failed = false)
  }

  /**
   * The title of the screen, to name it in the list of markers: in a browser, the page's, which the
   * outermost web document has; otherwise, or when the page has none, the active window's.
   */
  private fun titleOf(windows: List<AppWindow>, focus: AccessibilityNodeInfoCompat, browser: Boolean): String? {
    fun textOf(text: CharSequence?) = text?.toString()?.trim()?.ifEmpty { null }
    if (browser) {
      val document = MarkerCapture.ancestors(focus, Deadline(CAPTURE_MS)).lastOrNull(AccessibilityNodeInfoUtils::isWebDocument)
      document?.let { textOf(it.text) ?: textOf(it.contentDescription) }?.let { return it }
    }
    return textOf(windows.firstOrNull()?.window?.let(AccessibilityWindowInfoUtils::getTitle))
  }

  /** The marker of the screen with [key] whose item is [focus], or null. */
  private fun ownerOf(
    focus: AccessibilityNodeInfoCompat,
    key: ScreenKey,
    roots: List<AccessibilityNodeInfoCompat>,
    deadline: Deadline,
  ): Entry? {
    val entries = PlaceMarkerStore.screen(service, key)?.markers.orEmpty().map { Entry(key, it) }
    return found(entries, roots, deadline).firstOrNull { it.node == focus }?.entry
  }

  /** The markers of the screens that may be shown, most recently used first. */
  private fun entriesShownAt(key: ScreenKey): List<Entry> =
    PlaceMarkerStore.screensShownAt(service, key)
      .flatMap { screen -> screen.markers.map { Entry(screen.key, it) } }
      .sortedByDescending { it.marker.lastUsed }

  /** The markers of [entries] whose items are found, each item once, for the one used last. */
  private fun found(entries: List<Entry>, roots: List<AccessibilityNodeInfoCompat>, deadline: Deadline): List<FoundMarker> {
    val resolutions = resolver.resolveAll(entries, roots, deadline)
    return entries.indices
      .mapNotNull { i -> resolutions[i].node?.let { FoundMarker(entries[i], it) } }
      .distinctBy { it.node }
  }

  /**
   * Where to look for [marker]'s item in [list]. By the row it was at, near that row: there, or not
   * in this list, as in a chat whose screen shows other chats too. Without a row, the whole list,
   * up and then down.
   */
  private fun planToFind(marker: StoredMarker, list: AccessibilityNodeInfoCompat): MarkerScroller.Plan? {
    val row = marker.list?.row ?: return null
    val rows = shownRows(list)
    val restore = restoreRows(rows)
    if (row < 0 || rows.isEmpty()) {
      val whole = MarkerScroller.MAX_PAGES
      return MarkerScroller.Plan(emptyList(), listOf(MarkerScroller.Step(false, whole), MarkerScroller.Step(true, whole)), restore)
    }
    val jumps = jumpRows(marker, list)
    val around = listOf(MarkerScroller.Step(true, NEAR_PAGES), MarkerScroller.Step(false, 2 * NEAR_PAGES))
    // Rows can be numbered from the bottom, as in a chat.
    val downward = rows.size < 2 || rows.first() < rows.last()
    val distance = if (row > rows.max()) row - rows.max() else if (row < rows.min()) rows.min() - row else 0
    if (jumps.isNotEmpty() || distance == 0) return MarkerScroller.Plan(jumps, around, restore)
    val down = (row > rows.max()) == downward
    return MarkerScroller.Plan(
      emptyList(),
      listOf(MarkerScroller.Step(down, distance / rows.size + NEAR_PAGES)),
      restore,
    )
  }

  /**
   * The rows to jump to for [marker]'s item when [list] can jump: its row from the start, and from
   * the end, as when rows were added at the end.
   */
  private fun jumpRows(marker: StoredMarker, list: AccessibilityNodeInfoCompat): List<Int> {
    val place = marker.list ?: return emptyList()
    val rowCount = list.collectionInfo?.rowCount ?: -1
    if (place.row < 0 ||
      rowCount <= 0 ||
      !AccessibilityNodeInfoUtils.supportsAction(list, AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION.id)
    ) {
      return emptyList()
    }
    return listOf(place.row, rowCount - (place.rowCount - place.row)).filter { it in 0 until rowCount }.distinct()
  }

  /** The rows to jump to, the last one shown first, to bring the list back to show [rows]. */
  private fun restoreRows(rows: List<Int>): List<Int> =
    if (rows.isEmpty()) emptyList() else listOf(rows.last(), rows.first())

  /** The rows of [list] on screen, from the top, by their numbers in the list. */
  private fun shownRows(list: AccessibilityNodeInfoCompat): List<Int> {
    val rows = mutableListOf<Pair<Int, Int>>()
    for (i in 0 until list.childCount) {
      val child = list.getChild(i) ?: continue
      val row = child.collectionItemInfo?.rowIndex ?: continue
      if (!child.isVisibleToUser) continue
      rows += Rect().also(child::getBoundsInScreen).top to row
    }
    return rows.sortedBy { it.first }.map { it.second }
  }

  /**
   * The focused item, described as a marker within [millis], to focus again after a search moved
   * the list, or to go back to.
   */
  private fun origin(key: ScreenKey, focus: AccessibilityNodeInfoCompat?, millis: Long = CAPTURE_MS): Entry? =
    focus?.let { Entry(key, MarkerCapture.capture(it, Deadline(millis))(0, 0)) }

  private fun apply(eventId: EventId?, outcome: Outcome) {
    when (outcome) {
      is Outcome.Say -> say(eventId, outcome.text, outcome.failed)
      is Outcome.FocusOn -> focusOn(eventId, outcome.entry, outcome.node, outcome.from)
      is Outcome.ScrollFor -> scrollFor(eventId, outcome)
      is Outcome.Manage -> PlaceMarkersFragment.open(service, outcome.current)
      Outcome.End -> end(eventId)
    }
  }

  private fun scrollFor(eventId: EventId?, outcome: Outcome.ScrollFor) {
    val id = request
    scroller.start(eventId, outcome.list, outcome.plan, find = { list -> findInList(outcome, list) }) { found ->
      if (id != request) return@start
      if (found != null) {
        found.place?.let { PlaceMarkerStore.update(service, found.entry.key, found.entry.marker.copy(list = it)) }
        focusOn(eventId, found.entry, found.node, outcome.from)
      } else {
        refocusOrigin(eventId, outcome, id)
      }
    }
  }

  // Runs on the background thread, after each scroll.
  private fun findInList(outcome: Outcome.ScrollFor, list: AccessibilityNodeInfoCompat): FoundMarker? {
    val deadline = Deadline(SEARCH_MS)
    val resolutions = resolver.resolveInList(outcome.entries.map { it.marker }, list, deadline)
    val found =
      outcome.entries.indices.mapNotNull { i -> resolutions[i].node?.let { FoundMarker(outcome.entries[i], it) } }
    val target = DocumentOrder(listOf(list)).nearest(found, null, outcome.forward, deadline) { it.node } ?: return null
    // The row the item is at now, so that the next search jumps right to it.
    val chain = MarkerCapture.ancestors(target.node, deadline)
    val listAt = chain.indexOf(list)
    val row = if (listAt > 0) MarkerCapture.rowOf(chain, listAt) else -1
    val rowCount = list.collectionInfo?.rowCount ?: -1
    val place = target.entry.marker.list
    return if (place != null && row >= 0 && rowCount > 0 && (row != place.row || rowCount != place.rowCount)) {
      target.copy(place = place.copy(row = row, rowCount = rowCount))
    } else {
      target
    }
  }

  /**
   * After a search that didn't find its item, focuses again, without speaking, the item that had
   * focus, as the list may have reused its row for another while it scrolled, and then says what
   * wasn't found.
   */
  private fun refocusOrigin(eventId: EventId?, outcome: Outcome.ScrollFor, id: Int) {
    val origin = outcome.origin
    val sent =
      origin != null &&
        inBackground {
          val node =
            try {
              val roots = appWindows(origin.key.packageName).map { it.root }
              resolver.resolveAll(listOf(origin), roots, Deadline(SEARCH_MS)).single().node
            } catch (t: Throwable) {
              null
            }
          main.post {
            if (id != request) return@post
            if (node != null && !node.isAccessibilityFocused) {
              pipeline.returnFeedback(
                eventId,
                Feedback.focus(
                  node,
                  FocusActionInfo.builder()
                    .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
                    .setForceMuteFeedback(true)
                    .build(),
                ),
              )
            }
            apply(eventId, outcome.notFound)
          }
        }
    if (!sent) apply(eventId, outcome.notFound)
  }

  /**
   * Focuses [node], the item of [entry], a marker or the last spot. [from] describes the item focus
   * moves from, to become the last spot, or is null to keep it.
   */
  private fun focusOn(eventId: EventId?, entry: Entry, node: AccessibilityNodeInfoCompat, from: Entry?) {
    live.put(entry.key, entry.marker.number, node)
    focuser.focus(eventId, node, forceRefocus = true)
    val back = entry.marker.number == LastSpot.NUMBER
    val origin = commandFocus
    lastSpot.jumped(back, origin, from, node)
    if (from != null && origin != null) live.put(from.key, LastSpot.NUMBER, origin)
    if (back) return
    PlaceMarkerStore.markUsed(service, entry.key, entry.marker.number, System.currentTimeMillis())
    main.removeCallbacks(flush)
    main.postDelayed(flush, FLUSH_DELAY_MS)
  }

  private fun say(eventId: EventId?, text: String, failed: Boolean) {
    pipeline.returnFeedback(eventId, Feedback.speech(text))
    if (failed) end(eventId)
  }

  private fun end(eventId: EventId?) {
    pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete))
  }

  /** The windows of [packageName] with their roots, the active one first, without keyboards. */
  private fun appWindows(packageName: String): List<AppWindow> =
    AccessibilityServiceCompatUtils.getWindows(service)
      .filter {
        it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
          it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
      }
      .sortedByDescending { it.isActive }
      .mapNotNull { window -> AccessibilityWindowInfoUtils.getRootCompat(window)?.let { AppWindow(window, it) } }
      .filter { it.root.packageName?.toString() == packageName }

  private class AppWindow(val window: AccessibilityWindowInfo, val root: AccessibilityNodeInfoCompat)

  private data class FoundMarker(val entry: Entry, val node: AccessibilityNodeInfoCompat, val place: ListPlace? = null)

  private sealed interface Outcome {
    data class Say(val text: String, val failed: Boolean) : Outcome

    /** Focuses [node], the item of [entry]; [from] describes the item to become the last spot. */
    data class FocusOn(val entry: Entry, val node: AccessibilityNodeInfoCompat, val from: Entry?) : Outcome

    /**
     * Scrolls [list] by [plan] for the items of [entries], else gives [notFound]. [origin] describes
     * the item focus was on, to focus it again after a search that found nothing, and [from] is
     * [origin] when it is to become the last spot.
     */
    data class ScrollFor(
      val entries: List<Entry>,
      val list: AccessibilityNodeInfoCompat,
      val plan: MarkerScroller.Plan,
      val forward: Boolean,
      val notFound: Outcome,
      val origin: Entry?,
      val from: Entry?,
    ) : Outcome

    /** Opens the list of markers, with the screen with [current] first. */
    data class Manage(val current: ScreenKey?) : Outcome

    data object End : Outcome
  }

  companion object {
    private const val TAG = "PlaceMarkers"

    /** The longest a command reads the screen for. */
    private const val SEARCH_MS = 300L

    /** The longest setting a marker reads its item's surroundings for. */
    private const val CAPTURE_MS = 300L

    /** The longest a jump reads the item it leaves for, to go back to, as the jump waits for it. */
    private const val SPOT_CAPTURE_MS = 100L

    /** The time after a marker is used until its use is written. */
    private const val FLUSH_DELAY_MS = 10_000L

    /** The most characters said of the item a moved marker was on. */
    private const val OLD_TEXT_SPOKEN = 60

    /** The pages scrolled past where a marker's row should be, as rows may have been added. */
    private const val EXTRA_PAGES = 2

    /** The pages looked through on each side of the row a marker was at. */
    private const val NEAR_PAGES = 3

    @Volatile private var active: PlaceMarkerNavigator? = null

    /** Takes the events SandFly gets, for the scroll events of a list being searched. */
    @JvmStatic
    fun onAccessibilityEvent(event: AccessibilityEvent) {
      if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
      active?.scroller?.onScrolled(event)
    }
  }
}
