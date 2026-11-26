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

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.compositor.AccessibilityNodeFeedbackUtils

/** The names the list of markers shows for apps, screens and markers. */
internal object PlaceMarkerNames {
  private val SPACES = Regex("\\s+")

  /** The name of the app [packageName], or [packageName] when it isn't installed. */
  fun appName(context: Context, packageName: String): String =
    try {
      val packageManager = context.packageManager
      packageManager
        .getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
        .toString()
        .ifBlank { packageName }
    } catch (e: PackageManager.NameNotFoundException) {
      packageName
    }

  /**
   * The name of [screen] of the app named [appName]: the title it had when a marker was set, unless
   * that is only the app's name, else "Page" for a page in a browser, or the name of its activity.
   */
  fun screenName(context: Context, screen: StoredScreen, appName: String): String {
    val title = screen.title?.replace(SPACES, " ")?.trim()
    if (!title.isNullOrEmpty() && !title.equals(appName, ignoreCase = true)) return title
    if (screen.key.page != null) return context.getString(R.string.place_markers_page)
    return activityName(screen.key.activity) ?: appName
  }

  /**
   * The name of the activity class [activity] without its package and an "Activity" at its end, as
   * "Conversation" for com.whatsapp.Conversation, or null without one.
   */
  fun activityName(activity: String?): String? {
    val name = activity?.substringAfterLast('.')?.substringAfterLast('$')?.ifEmpty { null } ?: return null
    return name.removeSuffix("Activity").ifEmpty { name }
  }

  /** What the list says of [marker]: its item's text, else the item's role, else "No text". */
  fun markerSummary(context: Context, marker: StoredMarker): CharSequence =
    marker.text
      ?: AccessibilityNodeFeedbackUtils.getRoleName(marker.role, context).takeIf { it.isNotEmpty() }
      ?: context.getString(R.string.place_marker_no_text)
}
