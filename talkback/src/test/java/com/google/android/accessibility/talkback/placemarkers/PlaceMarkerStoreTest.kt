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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceMarkerStoreTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Before
  @After
  fun clear() {
    context.getSharedPreferences(PlaceMarkerStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    PlaceMarkerStore.clearCache()
  }

  private fun marker(number: Int, text: String? = "item $number") =
    StoredMarker(
      number = number,
      lastUsed = 10L * number,
      className = "android.widget.TextView",
      role = 3,
      text = text,
      viewId = "app:id/item",
      anchorId = "app:id/content",
      web = false,
      path = listOf(PathStep("android.widget.FrameLayout", null, 2), PathStep("android.widget.TextView", "app:id/item", 0)),
      before = "before",
      after = null,
      list = ListPlace(depth = 0, row = 7, rowCount = 40),
    )

  @Test
  fun markersAreReadBackAsTheyWereSaved() {
    val screens =
      listOf(
        StoredScreen(ScreenKey("app", "Main", "0123456789abcdef"), 5, listOf(marker(1), marker(4, text = null)), "Inbox"),
        StoredScreen(ScreenKey("app", "Other", null), 4, listOf(marker(2))),
      )
    assertEquals(screens, PlaceMarkerStore.parse(PlaceMarkerStore.serialize(screens)))
  }

  @Test
  fun screensSavedWithoutATitleAreReadBack() {
    val screen = PlaceMarkerStore.parse("""[{"v":1,"p":"app","a":"Main","u":1,"m":[{"n":1,"t":"a"}]}]""").single()
    assertNull(screen.title)
    assertEquals("a", screen.markers.single().text)
  }

  @Test
  fun aScreenKeepsItsTitleUntilAnotherIsGiven() {
    val key = ScreenKey("app", "Main", null)
    PlaceMarkerStore.put(context, key, listOf(marker(1)), now = 100, title = "Inbox")
    PlaceMarkerStore.put(context, key, listOf(marker(1), marker(2)), now = 200)
    PlaceMarkerStore.clearCache()
    assertEquals("Inbox", PlaceMarkerStore.screen(context, key)!!.title)
    PlaceMarkerStore.put(context, key, listOf(marker(1)), now = 300, title = "Sent")
    assertEquals("Sent", PlaceMarkerStore.screen(context, key)!!.title)
  }

  @Test
  fun deletingKeepsWhenScreensWereUsed() {
    val main = ScreenKey("app", "Main", null)
    val other = ScreenKey("app", "Other", null)
    PlaceMarkerStore.put(context, main, listOf(marker(1), marker(2)), now = 100)
    PlaceMarkerStore.put(context, other, listOf(marker(3)), now = 200)
    assertTrue(PlaceMarkerStore.remove(context, main, 1))
    assertFalse(PlaceMarkerStore.remove(context, main, 1))
    PlaceMarkerStore.clearCache()
    val screens = PlaceMarkerStore.screens(context)
    assertEquals(listOf(other, main), screens.map { it.key })
    assertEquals(100L, screens[1].lastUsed)
    assertEquals(listOf(2), screens[1].markers.map { it.number })
    // The screen goes with its last marker.
    assertTrue(PlaceMarkerStore.remove(context, main, 2))
    assertNull(PlaceMarkerStore.screen(context, main))
  }

  @Test
  fun screensAndEveryMarkerAreDeletedWithTheirCount() {
    val main = ScreenKey("app", "Main", null)
    PlaceMarkerStore.put(context, main, listOf(marker(1), marker(2)), now = 100)
    PlaceMarkerStore.put(context, ScreenKey("app", "Other", null), listOf(marker(3)), now = 200)
    PlaceMarkerStore.put(context, ScreenKey("browser", "Main", "page"), listOf(marker(4), marker(5)), now = 300)
    assertEquals(2, PlaceMarkerStore.removeScreen(context, main))
    assertEquals(0, PlaceMarkerStore.removeScreen(context, main))
    assertEquals(3, PlaceMarkerStore.removeAll(context))
    PlaceMarkerStore.clearCache()
    assertTrue(PlaceMarkerStore.screens(context).isEmpty())
  }

  @Test
  fun whatIsNotValidIsLeftOut() {
    assertTrue(PlaceMarkerStore.parse("not json").isEmpty())
    assertTrue(PlaceMarkerStore.parse("""[{"v":2,"p":"app","m":[{"n":1}]}]""").isEmpty())
    val parsed =
      PlaceMarkerStore.parse(
        """[{"v":1,"p":"app","u":1,"m":[{"n":1,"t":"a"},{"n":1,"t":"b"},{"n":11},{"n":2,"l":{"d":5}}]}]"""
      )
    val markers = parsed.single().markers
    assertEquals(listOf(1, 2), markers.map { it.number })
    assertEquals("a", markers[0].text)
    // A list deeper than the path is left out.
    assertNull(markers[1].list)
  }

  @Test
  fun longTextIsCut() {
    val long = "x".repeat(PlaceMarkerStore.MAX_TEXT + 50)
    val screens = listOf(StoredScreen(ScreenKey("app", "Main", null), 1, listOf(marker(1, text = long))))
    val text = PlaceMarkerStore.parse(PlaceMarkerStore.serialize(screens)).single().markers.single().text
    assertEquals(PlaceMarkerStore.MAX_TEXT, text!!.length)
  }

  @Test
  fun markersAreSavedAndDeletedByScreen() {
    val key = ScreenKey("app", "Main", null)
    PlaceMarkerStore.put(context, key, listOf(marker(1), marker(2)), now = 100)
    PlaceMarkerStore.clearCache()
    assertEquals(listOf(1, 2), PlaceMarkerStore.screen(context, key)!!.markers.map { it.number })
    PlaceMarkerStore.put(context, key, emptyList(), now = 200)
    PlaceMarkerStore.clearCache()
    assertNull(PlaceMarkerStore.screen(context, key))
  }

  @Test
  fun theScreenUsedLongestAgoGoesFirst() {
    for (i in 0..PlaceMarkerStore.MAX_SCREENS) {
      PlaceMarkerStore.put(context, ScreenKey("app", "Screen$i", null), listOf(marker(1)), now = 1000L + i)
    }
    PlaceMarkerStore.clearCache()
    val screens = PlaceMarkerStore.screens(context)
    assertEquals(PlaceMarkerStore.MAX_SCREENS, screens.size)
    assertNull(screens.firstOrNull { it.key.activity == "Screen0" })
  }

  @Test
  fun usesAreWrittenLater() {
    val key = ScreenKey("app", "Main", null)
    PlaceMarkerStore.put(context, key, listOf(marker(1)), now = 100)
    PlaceMarkerStore.markUsed(context, key, 1, now = 500)
    assertEquals(500L, PlaceMarkerStore.screen(context, key)!!.markers.single().lastUsed)
    PlaceMarkerStore.flush(context)
    PlaceMarkerStore.clearCache()
    assertEquals(500L, PlaceMarkerStore.screen(context, key)!!.markers.single().lastUsed)
  }

  @Test
  fun screensShownAtTakeTheUnknownPartsIntoAccount() {
    PlaceMarkerStore.put(context, ScreenKey("app", "Main", "page"), listOf(marker(1)), now = 100)
    PlaceMarkerStore.put(context, ScreenKey("app", "Main", null), listOf(marker(2)), now = 100)
    PlaceMarkerStore.put(context, ScreenKey("app", "Other", null), listOf(marker(3)), now = 100)
    assertEquals(2, PlaceMarkerStore.screensShownAt(context, ScreenKey("app", "Main", "page")).size)
    assertEquals(3, PlaceMarkerStore.screensShownAt(context, ScreenKey("app", null, null)).size)
  }
}
