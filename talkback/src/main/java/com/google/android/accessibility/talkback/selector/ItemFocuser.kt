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

package com.google.android.accessibility.talkback.selector

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.Performance.EventId

/**
 * Moves accessibility focus to an item that reading controls move to, first scrolling it wholly
 * into view when part of it is off screen, as a swipe does: parts that are off screen aren't
 * spoken, so a row half under a toolbar would be read without its title.
 */
class ItemFocuser(private val pipeline: Pipeline.FeedbackReturner) {
  private val handler = Handler(Looper.getMainLooper())

  /**
   * Focuses [target]. With [forceRefocus], focus that is already on it moves to it again, so it is
   * spoken again. Plays the end sound when [target] is gone after scrolling.
   */
  fun focus(eventId: EventId?, target: AccessibilityNodeInfoCompat, forceRefocus: Boolean = false) {
    handler.removeCallbacksAndMessages(null)
    val list = VerticalNavigation.listOf(target)
    if (list != null &&
        hasPartOffScreen(target) &&
        // Recorded as SandFly's own scroll, so it isn't taken for the user's and focus stays.
        pipeline.returnFeedback(eventId, Feedback.scrollEnsureOnScreen(list, target))
    ) {
      handler.postDelayed(
        {
          if (target.refresh() && target.isVisibleToUser) {
            focusNow(eventId, target, forceRefocus)
          } else {
            pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete))
          }
        },
        SHOW_ON_SCREEN_WAIT_MS,
      )
      return
    }
    focusNow(eventId, target, forceRefocus)
  }

  /** Stops a focus waiting for its item to scroll into view. */
  fun cancel() {
    handler.removeCallbacksAndMessages(null)
  }

  private fun focusNow(eventId: EventId?, target: AccessibilityNodeInfoCompat, forceRefocus: Boolean) {
    pipeline.returnFeedback(
      eventId,
      Feedback.focus(
          target,
          FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),
        )
        .setForceRefocus(forceRefocus),
    )
  }

  /** Whether the screen cuts off part of [node]: it is shown smaller than it is laid out. */
  private fun hasPartOffScreen(node: AccessibilityNodeInfoCompat): Boolean {
    val onScreen = Rect().also { node.getBoundsInScreen(it) }
    val laidOut = Rect().also { node.getBoundsInParent(it) }
    return onScreen.height() < laidOut.height() || onScreen.width() < laidOut.width()
  }

  private companion object {
    /** The time a list takes to bring an item wholly into view. */
    const val SHOW_ON_SCREEN_WAIT_MS = 150L
  }
}
