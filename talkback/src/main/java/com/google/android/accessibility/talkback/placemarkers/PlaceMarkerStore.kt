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
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The saved place markers, by screen. Kept in a file of their own, which settings backups leave out
 * and settings profiles don't change, as markers hold the text of items in other apps. The file is
 * in storage encrypted until the person first unlocks the device; until then no markers are read
 * or kept.
 */
object PlaceMarkerStore {
  /** The name of the markers' file. */
  const val FILE = "place_markers"

  private const val KEY = "screens"
  private const val VERSION = 1

  /** The most screens with markers kept; the one used longest ago goes first. */
  @VisibleForTesting const val MAX_SCREENS = 50

  /** The most characters of all saved markers; the screens used longest ago go first. */
  @VisibleForTesting const val MAX_TOTAL_LENGTH = 400_000

  const val MAX_TEXT = 300
  const val MAX_SIDE_TEXT = 100
  const val MAX_NAME = 200
  const val MAX_PATH = 40

  private val lock = Any()

  /** The screens as last read or written, so that each command doesn't parse the file again. */
  private var cached: List<StoredScreen>? = null

  /** Whether [cached] has uses not yet written. */
  private var dirty = false

  /**
   * Returns every screen with markers, used most recently first, or none until the device is first
   * unlocked.
   */
  fun screens(context: Context): List<StoredScreen> =
    synchronized(lock) {
      cached?.let {
        return it
      }
      // Not kept while the file can't be read, so that nothing written then replaces it.
      val prefs = prefs(context) ?: return emptyList()
      (prefs.getString(KEY, null)?.let(::parse) ?: emptyList()).also { cached = it }
    }

  /** Returns the saved screens whose markers may be on the screen with [key]. */
  fun screensShownAt(context: Context, key: ScreenKey): List<StoredScreen> =
    screens(context).filter { key.mayShow(it.key) }

  /** Returns the screen with exactly [key], or null when it has no markers. */
  fun screen(context: Context, key: ScreenKey): StoredScreen? =
    screens(context).firstOrNull { it.key == key }

  /**
   * Saves [markers] as all the markers of the screen with [key], used at [now], and writes the
   * file. The screen keeps the title it had unless [title] is given.
   */
  fun put(context: Context, key: ScreenKey, markers: List<StoredMarker>, now: Long, title: String? = null) {
    synchronized(lock) {
      val prefs = prefs(context) ?: return
      val screens = screens(context)
      val others = screens.filter { it.key != key }
      val kept = title?.take(MAX_NAME)?.ifEmpty { null } ?: screens.firstOrNull { it.key == key }?.title
      val updated =
        if (markers.isEmpty()) others else listOf(StoredScreen(key, now, markers, kept)) + others
      val (saved, json) = within(updated)
      cached = saved
      dirty = false
      prefs.edit().putString(KEY, json).apply()
    }
  }

  /**
   * Deletes the marker [number] of the screen with [key], and the screen when it was its last, and
   * writes the file. Returns whether there was such a marker. When the screen was used doesn't
   * change, so the list of markers keeps its order.
   */
  fun remove(context: Context, key: ScreenKey, number: Int): Boolean =
    removeWhere(context) { screen, marker -> screen.key == key && marker.number == number } > 0

  /** Deletes every marker of the screen with [key], writes the file, and returns how many. */
  fun removeScreen(context: Context, key: ScreenKey): Int =
    removeWhere(context) { screen, _ -> screen.key == key }

  /** Deletes every marker of every screen, writes the file, and returns how many. */
  fun removeAll(context: Context): Int = removeWhere(context) { _, _ -> true }

  private fun removeWhere(context: Context, removed: (StoredScreen, StoredMarker) -> Boolean): Int =
    synchronized(lock) {
      val prefs = prefs(context) ?: return 0
      var count = 0
      val screens =
        screens(context).mapNotNull { screen ->
          val markers = screen.markers.filterNot { removed(screen, it) }
          count += screen.markers.size - markers.size
          if (markers.isEmpty()) null else screen.copy(markers = markers)
        }
      if (count == 0) return 0
      cached = screens
      // Uses noted in memory are written with the rest.
      dirty = false
      prefs.edit().putString(KEY, serialize(screens)).apply()
      count
    }

  /**
   * Notes that the marker [number] of the screen with [key] was used at [now], which only decides
   * which marker "Set marker" replaces first, so it is written later by [flush].
   */
  fun markUsed(context: Context, key: ScreenKey, number: Int, now: Long) =
    change(context, key, number) { screen, marker ->
      screen.copy(lastUsed = now) to marker.copy(lastUsed = now)
    }

  /** Replaces the saved marker of the same screen and number with [marker], written later. */
  fun update(context: Context, key: ScreenKey, marker: StoredMarker) =
    change(context, key, marker.number) { screen, _ -> screen to marker }

  /** Changes the marker [number] of the screen with [key] in memory, to be written by [flush]. */
  private fun change(
    context: Context,
    key: ScreenKey,
    number: Int,
    change: (StoredScreen, StoredMarker) -> Pair<StoredScreen, StoredMarker>,
  ) {
    synchronized(lock) {
      val screens = screens(context)
      val screen = screens.firstOrNull { it.key == key } ?: return
      val marker = screen.markers.firstOrNull { it.number == number } ?: return
      val (changedScreen, changedMarker) = change(screen, marker)
      val markers = screen.markers.map { if (it === marker) changedMarker else it }
      cached = screens.map { if (it === screen) changedScreen.copy(markers = markers) else it }
      dirty = true
    }
  }

  /** Writes the uses noted since the last write. */
  fun flush(context: Context) {
    synchronized(lock) {
      val screens = cached
      if (!dirty || screens == null) return
      dirty = false
      prefs(context)?.edit()?.putString(KEY, serialize(screens))?.apply()
    }
  }

  /** Forgets what was read, such as after the file changed or for a test. */
  @VisibleForTesting
  fun clearCache() {
    synchronized(lock) {
      cached = null
      dirty = false
    }
  }

  private fun prefs(context: Context): SharedPreferences? {
    if (!UserManagerCompat.isUserUnlocked(context)) return null
    return (context.applicationContext ?: context).getSharedPreferences(FILE, Context.MODE_PRIVATE)
  }

  /**
   * The screens used most recently, as many as fit in [MAX_SCREENS] and [MAX_TOTAL_LENGTH], and
   * their JSON.
   */
  private fun within(screens: List<StoredScreen>): Pair<List<StoredScreen>, String> {
    val kept = mutableListOf<StoredScreen>()
    val json = JSONArray()
    var total = 0
    for (screen in screens.sortedByDescending { it.lastUsed }) {
      if (kept.size >= MAX_SCREENS) break
      val screenJson = screenJson(screen)
      val length = screenJson.toString().length
      if (total + length > MAX_TOTAL_LENGTH) break
      total += length
      kept += screen
      json.put(screenJson)
    }
    return kept to json.toString()
  }

  @VisibleForTesting
  fun serialize(screens: List<StoredScreen>): String =
    JSONArray().apply { screens.forEach { put(screenJson(it)) } }.toString()

  private fun screenJson(screen: StoredScreen): JSONObject =
    JSONObject()
      .put("v", VERSION)
      .put("p", screen.key.packageName)
      .putOpt("a", screen.key.activity)
      .putOpt("g", screen.key.page)
      .put("u", screen.lastUsed)
      .putOpt("t", screen.title)
      .put("m", JSONArray().apply { screen.markers.forEach { put(markerJson(it)) } })

  private fun markerJson(marker: StoredMarker): JSONObject =
    JSONObject()
      .put("n", marker.number)
      .put("u", marker.lastUsed)
      .putOpt("c", marker.className)
      .put("r", marker.role)
      .putOpt("t", marker.text)
      .putOpt("id", marker.viewId)
      .putOpt("an", marker.anchorId)
      .put("w", marker.web)
      .put(
        "p",
        JSONArray().apply {
          marker.path.forEach {
            put(JSONObject().putOpt("c", it.className).putOpt("id", it.viewId).put("i", it.index))
          }
        },
      )
      .putOpt("b", marker.before)
      .putOpt("f", marker.after)
      .putOpt(
        "l",
        marker.list?.let {
          JSONObject().put("d", it.depth).put("row", it.row).put("rc", it.rowCount)
        },
      )

  /** Returns the screens in [json], leaving out anything not valid, or none when it isn't JSON. */
  @VisibleForTesting
  fun parse(json: String): List<StoredScreen> =
    try {
      val array = JSONArray(json)
      val screens = mutableListOf<StoredScreen>()
      val keys = HashSet<ScreenKey>()
      for (i in 0 until minOf(array.length(), MAX_SCREENS)) {
        val screen = array.optJSONObject(i)?.let(::parseScreen) ?: continue
        if (keys.add(screen.key)) screens += screen
      }
      screens
    } catch (e: JSONException) {
      emptyList()
    }

  private fun parseScreen(json: JSONObject): StoredScreen? {
    if (json.optInt("v") != VERSION) return null
    val packageName = string(json, "p", MAX_NAME) ?: return null
    val key = ScreenKey(packageName, string(json, "a", MAX_NAME), string(json, "g", MAX_NAME))
    val array = json.optJSONArray("m") ?: return null
    val markers = mutableListOf<StoredMarker>()
    for (i in 0 until minOf(array.length(), PlaceMarkerCommands.COUNT)) {
      val marker = array.optJSONObject(i)?.let(::parseMarker) ?: continue
      if (markers.none { it.number == marker.number }) markers += marker
    }
    if (markers.isEmpty()) return null
    return StoredScreen(key, json.optLong("u"), markers.sortedBy { it.number }, string(json, "t", MAX_NAME))
  }

  private fun parseMarker(json: JSONObject): StoredMarker? {
    val number = json.optInt("n")
    if (number !in 1..PlaceMarkerCommands.COUNT) return null
    val steps = json.optJSONArray("p")
    val path = mutableListOf<PathStep>()
    if (steps != null && steps.length() <= MAX_PATH) {
      for (i in 0 until steps.length()) {
        val step = steps.optJSONObject(i) ?: return null
        path += PathStep(string(step, "c", MAX_NAME), string(step, "id", MAX_NAME), step.optInt("i", -1))
      }
    }
    val list =
      json.optJSONObject("l")?.let {
        val depth = it.optInt("d", -2)
        if (depth < ListPlace.UNKNOWN || depth >= path.size) null
        else ListPlace(depth, it.optInt("row", -1), it.optInt("rc", -1))
      }
    return StoredMarker(
      number = number,
      lastUsed = json.optLong("u"),
      className = string(json, "c", MAX_NAME),
      role = json.optInt("r"),
      text = string(json, "t", MAX_TEXT),
      viewId = string(json, "id", MAX_NAME),
      anchorId = string(json, "an", MAX_NAME),
      web = json.optBoolean("w"),
      path = path,
      before = string(json, "b", MAX_SIDE_TEXT),
      after = string(json, "f", MAX_SIDE_TEXT),
      list = list,
    )
  }

  /** The string [name] of [json], cut to [max] characters, or null when it has none. */
  private fun string(json: JSONObject, name: String, max: Int): String? =
    if (json.isNull(name)) null else json.optString(name, "").take(max).ifEmpty { null }
}
