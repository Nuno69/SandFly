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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.input.ScrollEventInterpreter.ScrollTimeout
import com.google.android.accessibility.utils.output.ScrollActionRecord
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Scrolls a list to find a marker's item that isn't on screen. It first jumps to rows where the
 * item may be, when the list can, then scrolls a page at a time, waiting for the list's scroll
 * events rather than a fixed time, and looking only at the list's rows after each page. A quiet
 * sound plays for each page. When the item isn't found, the list goes back to where it was.
 * Runs on the main thread; the list is read on a background thread.
 */
internal class MarkerScroller(
  private val pipeline: Pipeline.FeedbackReturner,
  private val background: (Runnable) -> Boolean,
) {
  /**
   * Where to look: first the [jumpRows], then a page at a time as each of [steps] says in turn.
   * [restoreRows] are rows on screen at the start, the last one first, to jump back to.
   */
  class Plan(val jumpRows: List<Int>, val steps: List<Step>, val restoreRows: List<Int>)

  /** Pages to scroll [down] or up, until the end of the list or [maxPages]. */
  class Step(val down: Boolean, val maxPages: Int)

  private val handler = Handler(Looper.getMainLooper())
  private var search: Search<*>? = null

  /** Whether a search is running. */
  val isSearching: Boolean
    get() = search != null

  /**
   * Starts looking in [list] by [plan], with [find] on a background thread after each scroll,
   * calling [done] with what it found, or with null when nothing was found and the list is back
   * where it was. A search running before stops.
   */
  fun <T : Any> start(
    eventId: EventId?,
    list: AccessibilityNodeInfoCompat,
    plan: Plan,
    find: (AccessibilityNodeInfoCompat) -> T?,
    done: (T?) -> Unit,
  ) {
    cancel()
    val started = Search(eventId, list, plan, find, done)
    search = started
    started.next()
  }

  /** Stops the search where it is, without calling its end, such as when the user takes over. */
  fun cancel() {
    search = null
    handler.removeCallbacksAndMessages(null)
  }

  /** Takes the scroll events of the list being searched. */
  fun onScrolled(event: AccessibilityEvent) {
    search?.onScrolled(event)
  }

  private inner class Search<T : Any>(
    val eventId: EventId?,
    /** The list, used on the main thread. */
    val list: AccessibilityNodeInfoCompat,
    val plan: Plan,
    val find: (AccessibilityNodeInfoCompat) -> T?,
    val done: (T?) -> Unit,
  ) {
    /** A copy of the list, refreshed on the background thread. */
    private val read = AccessibilityNodeInfoCompat.obtain(list)
    private var jump = 0
    private var direction = 0
    private var pages = 0

    /** Pages scrolled down, less pages scrolled up, to scroll back. */
    private var net = 0
    private var shown: String? = null
    private var restored = 0
    private var onSettled: (() -> Unit)? = null
    private var onNoEvent: (() -> Unit)? = null
    private var scrolling = false
    private val settled = Runnable { endWait(onSettled, "settled") }
    private val noEvent = Runnable { endWait(onNoEvent, "no scroll event") }
    private val waitedLongest = Runnable { endWait(onSettled, "still scrolling") }

    fun next() {
      if (search !== this) return
      if (jump < plan.jumpRows.size) {
        if (jumpTo(plan.jumpRows[jump++])) await(onScrolled = ::look, onNoEvent = ::look) else next()
        return
      }
      if (direction >= plan.steps.size) {
        restore()
        return
      }
      if (pages >= plan.steps[direction].maxPages || !page(plan.steps[direction].down)) {
        endDirection()
        return
      }
      await(onScrolled = ::lookAfterPage, onNoEvent = ::endDirection)
    }

    private fun endDirection() {
      LogUtils.d(TAG, "Direction %d ended after %d pages", direction, pages)
      direction++
      pages = 0
      next()
    }

    private fun lookAfterPage() {
      read { found, now ->
        when {
          found != null -> finish(found)
          // The list didn't move: it is at its end.
          now == null || now == shown -> endDirection()
          else -> {
            shown = now
            pages++
            net += if (plan.steps[direction].down) 1 else -1
            pipeline.returnFeedback(eventId, Feedback.sound(R.raw.scroll_tone))
            next()
          }
        }
      }
    }

    private fun look() {
      read { found, now ->
        shown = now
        if (found != null) finish(found) else next()
      }
    }

    /** Looks for the item, and reads what the list shows, to tell when it stopped moving. */
    private fun read(then: (T?, String?) -> Unit) {
      val sent =
        background(
          Runnable {
            var found: T? = null
            var now: String? = null
            try {
              if (read.refresh()) {
                found = find(read)
                now = shownRows(read)
              }
            } catch (t: Throwable) {
              found = null
            }
            handler.post { if (search === this) then(found, now) }
          }
        )
      if (!sent) restore()
    }

    private fun finish(found: T?) {
      if (search !== this) return
      LogUtils.d(TAG, "Search ended, found: %b, pages back: %d", found != null, restored)
      search = null
      handler.removeCallbacksAndMessages(null)
      done(found)
    }

    /** Takes the list back to where it was, then ends the search without the item. */
    private fun restore() {
      if (search !== this) return
      val moved = jump > 0 || net != 0
      if (moved && plan.restoreRows.isNotEmpty() && supportsJump()) {
        // Jumping to the last row shown and then the first leaves the rows where they were, from
        // either side.
        if (restored < plan.restoreRows.size && jumpTo(plan.restoreRows[restored++])) {
          await(onScrolled = ::restore, onNoEvent = ::restore)
        } else {
          finish(null)
        }
        return
      }
      if (net == 0 || restored >= MAX_PAGES) {
        finish(null)
        return
      }
      val down = net < 0
      if (!page(down)) {
        finish(null)
        return
      }
      restored++
      net += if (down) 1 else -1
      await(onScrolled = ::restore, onNoEvent = { finish(null) })
    }

    private fun await(onScrolled: () -> Unit, onNoEvent: () -> Unit) {
      onSettled = onScrolled
      this.onNoEvent = onNoEvent
      scrolling = false
      handler.removeCallbacks(settled)
      handler.postDelayed(noEvent, NO_EVENT_MS)
    }

    private fun endWait(then: (() -> Unit)?, how: String) {
      LogUtils.d(TAG, "Wait ended: %s", how)
      handler.removeCallbacks(settled)
      handler.removeCallbacks(noEvent)
      handler.removeCallbacks(waitedLongest)
      onSettled = null
      onNoEvent = null
      if (search === this) then?.invoke()
    }

    fun onScrolled(event: AccessibilityEvent) {
      // Only the list's own events: a carousel or another list in the window scrolls too.
      if (onSettled == null ||
        event.windowId != list.windowId ||
        !TextUtils.equals(event.className, list.className)
      ) {
        return
      }
      // A list scrolls in steps, each with an event; it has settled when they stop, or after a
      // while if they don't.
      if (!scrolling) {
        scrolling = true
        handler.removeCallbacks(noEvent)
        handler.postDelayed(waitedLongest, MAX_WAIT_MS)
      }
      handler.removeCallbacks(settled)
      handler.postDelayed(settled, SETTLE_MS)
    }

    private fun supportsJump(): Boolean =
      AccessibilityNodeInfoUtils.supportsAction(list, AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION.id)

    private fun jumpTo(row: Int): Boolean {
      if (!supportsJump()) return false
      LogUtils.d(TAG, "Jump to row %d", row)
      val arguments =
        Bundle().apply {
          putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, row)
          putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
        }
      return pipeline.returnFeedback(
        eventId,
        Feedback.scrollToPosition(
          list,
          ScrollActionRecord.ACTION_AUTO_SCROLL,
          AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION.id,
          arguments,
          // Says nothing, as when SandFly scrolls a list to collect its text.
          ScrollActionRecord.COLLECT_TEXT,
          ScrollTimeout.SCROLL_TIMEOUT_SHORT,
          /* autoScrollAttempt= */ 0,
        ),
      )
    }

    private fun page(down: Boolean): Boolean {
      val directed =
        if (down) AccessibilityActionCompat.ACTION_SCROLL_DOWN.id
        else AccessibilityActionCompat.ACTION_SCROLL_UP.id
      val action =
        if (AccessibilityNodeInfoUtils.supportsAction(list, directed)) directed
        else if (down) AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD
      LogUtils.d(TAG, "Page %s", if (down) "down" else "up")
      return AccessibilityNodeInfoUtils.supportsAction(list, action) &&
        pipeline.returnFeedback(
          eventId,
          Feedback.scroll(
            list,
            ScrollActionRecord.ACTION_AUTO_SCROLL,
            action,
            ScrollActionRecord.COLLECT_TEXT,
            ScrollTimeout.SCROLL_TIMEOUT_SHORT,
            /* autoScrollAttempt= */ 0,
          ),
        )
    }
  }

  companion object {
    private const val TAG = "PlaceMarkers"

    /** The most pages scrolled one way, for a list that loads more without end. */
    const val MAX_PAGES = 1_000

    /** The time without scroll events after which a list has settled. */
    private const val SETTLE_MS = 150L

    /** The longest a list is waited for once it started scrolling. */
    private const val MAX_WAIT_MS = 1_500L

    /** The time after a scroll with no scroll event, when the list didn't move. */
    private const val NO_EVENT_MS = 700L

    /** The most rows of a list read to tell whether it moved. */
    private const val MAX_ROWS_READ = 30

    /**
     * The rows of [list] on screen from the top, by their numbers when the list has them, or else
     * their texts, to tell when the list moved.
     */
    fun shownRows(list: AccessibilityNodeInfoCompat): String {
      val rows = StringBuilder()
      for (i in 0 until minOf(list.childCount, MAX_ROWS_READ)) {
        val child = list.getChild(i) ?: continue
        rows.append(child.collectionItemInfo?.rowIndex ?: MarkerText.of(child, PlaceMarkerStore.MAX_SIDE_TEXT)).append('\n')
      }
      return "${list.childCount}\n$rows"
    }
  }
}
