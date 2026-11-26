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
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.ActionRow
import com.google.android.accessibility.talkback.notifications.NotificationCodeFragment
import com.google.android.accessibility.talkback.notifications.RowAction
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.utils.preference.BasePreferencesActivity.FRAGMENT_ARGS
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME

/**
 * The screens with place markers, opened from SandFly settings or by the Manage place markers
 * command, which puts the screen it was run on first. Each screen opens its markers, and its
 * markers, or every marker, can be deleted.
 */
class PlaceMarkersFragment : NotificationCodeFragment(R.string.title_place_markers) {

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val current = arguments?.let(::keyOf)
    val screens =
      PlaceMarkerStore.screens(context)
        .sortedWith(compareByDescending<StoredScreen> { it.key == current }.thenByDescending { it.lastUsed })
    if (screens.isEmpty()) {
      screen.note(R.string.place_markers_empty)
      return
    }
    screens.forEachIndexed { index, saved ->
      val app = PlaceMarkerNames.appName(context, saved.key.packageName)
      val name =
        if (saved.key == current) getString(R.string.place_markers_this_screen)
        else PlaceMarkerNames.screenName(context, saved, app)
      val count = saved.markers.size
      screen.addPreference(
        ActionRow(context).also {
          // Rows are kept by their place, so that focus stays on the row of a deleted screen.
          it.key = "screen$index"
          it.title = app
          it.summary = resources.getQuantityString(R.plurals.place_markers_screen_summary, count, name, count)
          it.isPersistent = false
          it.isIconSpaceReserved = false
          it.layoutResource = R.layout.listitem_2texts
          it.actions =
            listOf(RowAction(getString(R.string.shortcut_delete_screen_place_markers)) { deleteScreen(saved.key) })
          it.fragment = PlaceMarkerScreenFragment::class.java.name
          it.extras.putAll(argumentsOf(saved.key))
        }
      )
    }
    screen.action(getString(R.string.place_markers_delete_all)) {
      dialog()
        .setTitle(R.string.place_markers_delete_all_title)
        .setPositiveButton(R.string.place_markers_delete) { _, _ ->
          sayDeleted(context, PlaceMarkerStore.removeAll(context))
          rebuild()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }
  }

  private fun deleteScreen(key: ScreenKey) {
    sayDeleted(requireContext(), PlaceMarkerStore.removeScreen(requireContext(), key))
    rebuild()
  }

  companion object {
    private const val ARG_PACKAGE = "place_markers_package"
    private const val ARG_ACTIVITY = "place_markers_activity"
    private const val ARG_PAGE = "place_markers_page"

    /** Opens the list of markers from the service, with the screen with [current] first. */
    @JvmStatic
    fun open(context: Context, current: ScreenKey?) {
      context.startActivity(
        Intent(context, TalkBackPreferencesActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
          .putExtra(FRAGMENT_NAME, PlaceMarkersFragment::class.java.name)
          .putExtra(FRAGMENT_ARGS, current?.let(::argumentsOf) ?: Bundle())
      )
    }

    internal fun argumentsOf(key: ScreenKey): Bundle =
      Bundle().apply {
        putString(ARG_PACKAGE, key.packageName)
        putString(ARG_ACTIVITY, key.activity)
        putString(ARG_PAGE, key.page)
      }

    internal fun keyOf(arguments: Bundle): ScreenKey? =
      arguments.getString(ARG_PACKAGE)?.let {
        ScreenKey(it, arguments.getString(ARG_ACTIVITY), arguments.getString(ARG_PAGE))
      }
  }
}

/** The markers of one screen, opened from [PlaceMarkersFragment], to delete them. */
class PlaceMarkerScreenFragment : NotificationCodeFragment(R.string.title_place_markers) {
  private val key: ScreenKey?
    get() = arguments?.let(PlaceMarkersFragment::keyOf)

  override fun getTitle(): CharSequence {
    val context = context ?: return super.getTitle()
    val saved = key?.let { PlaceMarkerStore.screen(context, it) } ?: return super.getTitle()
    return PlaceMarkerNames.screenName(context, saved, PlaceMarkerNames.appName(context, saved.key.packageName))
  }

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val saved = key?.let { PlaceMarkerStore.screen(context, it) }
    if (saved == null) {
      // Its last marker was deleted, here or by a command.
      screen.note(R.string.place_markers_empty)
      leave()
      return
    }
    val deleteLabel = getString(R.string.shortcut_delete_place_marker)
    saved.markers.forEachIndexed { index, marker ->
      val title = getString(R.string.place_marker_number, marker.number)
      val delete = { deleteMarker(saved.key, marker.number) }
      val row =
        screen.action(title, PlaceMarkerNames.markerSummary(context, marker), listOf(RowAction(deleteLabel, delete))) {
          choose(title, listOf(deleteLabel)) { delete() }
        }
      // Rows are kept by their place, so that focus stays on the row of a deleted marker.
      row.key = "marker$index"
    }
    screen.action(getString(R.string.shortcut_delete_screen_place_markers)) {
      sayDeleted(context, PlaceMarkerStore.removeScreen(context, saved.key))
      leave()
    }
  }

  private fun deleteMarker(key: ScreenKey, number: Int) {
    val context = requireContext()
    if (PlaceMarkerStore.remove(context, key, number)) {
      Toast.makeText(context, getString(R.string.place_marker_deleted, number), Toast.LENGTH_SHORT).show()
    }
    rebuild()
  }

  /** Goes back to the list of screens. */
  private fun leave() {
    val manager = parentFragmentManager
    if (manager.backStackEntryCount > 0 && !manager.isStateSaved) manager.popBackStack()
  }
}

/** Says that [count] markers were deleted, when any were. */
private fun sayDeleted(context: Context, count: Int) {
  if (count == 0) return
  val text = context.resources.getQuantityString(R.plurals.place_markers_deleted, count, count)
  Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** Adds a row that only shows [text]. */
private fun PreferenceGroup.note(@StringRes text: Int) {
  addPreference(
    Preference(context).apply {
      setTitle(text)
      isPersistent = false
      isSelectable = false
      isIconSpaceReserved = false
    }
  )
}
