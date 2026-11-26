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

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Checks that a marker finds its item again after the screen changes, and never a wrong one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkerResolverTest {
  private val key = ScreenKey("app", "Main", null)
  private val resolver = MarkerResolver(LiveNodes())

  private fun mark(node: AccessibilityNodeInfoCompat): StoredMarker =
    MarkerCapture.capture(node, Deadline(10_000))(1, 0)

  private fun resolve(marker: StoredMarker, root: AccessibilityNodeInfoCompat, millis: Long = 10_000): Resolution =
    resolver.resolveAll(listOf(MarkerResolver.Entry(key, marker)), listOf(root), Deadline(millis)).single()

  @Test
  fun theSameScreenHasTheItemAtItsPosition() {
    val root = node(FRAME, null, null)
    val content = node(LINEAR, null, root)
    node(TEXT, "Inbox", content)
    val send = node(BUTTON, "Send", content)
    val found = resolve(mark(send), root)
    assertEquals(send, found.node)
    assertEquals(Tier.EXACT_PATH, found.tier)
  }

  @Test
  fun anItemThatMovedIsFoundByItsTextAndTheItemBesideIt() {
    val before = node(FRAME, null, null)
    val content = node(LINEAR, null, before)
    node(TEXT, "Inbox", content)
    val marker = mark(node(BUTTON, "Send", content))

    val after = node(FRAME, null, null)
    val moved = node(LINEAR, null, after)
    node(TEXT, "Banner", moved)
    node(TEXT, "Inbox", moved)
    val send = node(BUTTON, "Send", moved)
    val found = resolve(marker, after)
    assertEquals(send, found.node)
    assertEquals(Tier.PATH, found.tier)
  }

  @Test
  fun twoItemsThatMatchAsWellAreNeitherFocused() {
    val before = node(FRAME, null, null)
    val card = node(LINEAR, null, before)
    node(TEXT, "Name", card)
    val marker = mark(node(BUTTON, "Edit", card))

    val after = node(FRAME, null, null)
    node(TEXT, "Header", after)
    for (i in 0..1) {
      val copy = node(LINEAR, null, after)
      node(TEXT, "Name", copy)
      node(BUTTON, "Edit", copy)
    }
    val found = resolve(marker, after)
    assertNull(found.node)
    assertTrue(found.ambiguous)
  }

  @Test
  fun aMessageIsFoundOnlyBetweenTheMessagesItWasBetween() {
    val chat = node(FRAME, null, null)
    val list = list(chat)
    row(list, "A", 0)
    val message = row(list, "ok", 1)
    row(list, "B", 2)
    val marker = mark(message)
    assertTrue(marker.inList)
    assertEquals(1, marker.list!!.row)

    // Another chat with the same message.
    val other = node(FRAME, null, null)
    val otherList = list(other)
    row(otherList, "C", 0)
    row(otherList, "ok", 1)
    row(otherList, "D", 2)
    val notFound = resolve(marker, other)
    assertNull(notFound.node)

    // The same chat with a message more above.
    val later = node(FRAME, null, null)
    val laterList = list(later)
    row(laterList, "Z", 0)
    row(laterList, "A", 1)
    val same = row(laterList, "ok", 2)
    row(laterList, "B", 3)
    assertEquals(same, resolve(marker, later).node)
    assertEquals(same, resolver.resolveInList(listOf(marker), laterList, Deadline(10_000)).single().node)
    assertEquals(laterList, resolver.listOf(marker, listOf(later), Deadline(10_000)))
  }

  @Test
  fun aListThatDoesNotScrollIsStillAList() {
    val chat = node(FRAME, null, null)
    val list = node("androidx.recyclerview.widget.RecyclerView", null, chat) {
      collectionInfo = AccessibilityNodeInfo.CollectionInfo(3, 1, false)
    }
    row(list, "A", 0)
    val marker = mark(row(list, "ok", 1))
    row(list, "B", 2)
    assertTrue(marker.inList)

    val other = node(FRAME, null, null)
    val otherList = node("androidx.recyclerview.widget.RecyclerView", null, other) {
      collectionInfo = AccessibilityNodeInfo.CollectionInfo(3, 1, false)
    }
    row(otherList, "C", 0)
    row(otherList, "ok", 1)
    row(otherList, "D", 2)
    assertNull(resolve(marker, other).node)
  }

  @Test
  fun aRowWithNoTextBesideItIsNotTakenForTheMarkedOne() {
    val chat = node(FRAME, null, null)
    val list = list(chat)
    row(list, null, 0)
    val marker = mark(row(list, "ok", 1))
    assertFalse(marker.hasBeside)
    assertNull(resolve(marker, chat).node)
  }

  @Test
  fun twoRowsLikeTheMarkedOneAreNeitherFocused() {
    val chat = node(FRAME, null, null)
    val list = list(chat)
    row(list, "A", 0)
    val marker = mark(row(list, "ok", 1))
    row(list, "B", 2)

    val later = node(FRAME, null, null)
    val laterList = list(later)
    for (i in 0..1) {
      row(laterList, "A", 3 * i)
      row(laterList, "ok", 3 * i + 1)
      row(laterList, "B", 3 * i + 2)
    }
    val found = resolve(marker, later)
    assertNull(found.node)
    assertTrue(found.ambiguous)
  }

  @Test
  fun aSearchCutShortFindsNothing() {
    val before = node(FRAME, null, null)
    val content = node(LINEAR, null, before)
    node(TEXT, "Inbox", content)
    val marker = mark(node(BUTTON, "Send", content))
    val after = node(FRAME, null, null)
    val moved = node(LINEAR, null, after)
    node(TEXT, "Banner", moved)
    node(TEXT, "Inbox", moved)
    node(BUTTON, "Send", moved)
    val found = resolve(marker, after, millis = 0)
    assertNull(found.node)
    assertFalse(found.ambiguous)
  }

  @Test
  fun markersLeftAreFoundByOneReadOfTheWindow() {
    val before = node(FRAME, null, null)
    val content = node(LINEAR, null, before)
    val send = mark(node(BUTTON, "Send", content))
    val cancel = mark(node(BUTTON, "Cancel", content))

    val after = node(FRAME, null, null)
    val moved = node(LINEAR, null, node(FRAME, null, after))
    val sendNow = node(BUTTON, "Send", moved)
    val cancelNow = node(BUTTON, "Cancel", moved)
    val found =
      resolver.resolveAll(
        listOf(MarkerResolver.Entry(key, send), MarkerResolver.Entry(key, cancel)),
        listOf(after),
        Deadline(10_000),
      )
    assertEquals(listOf(sendNow, cancelNow), found.map { it.node })
    assertEquals(listOf(Tier.SCAN, Tier.SCAN), found.map { it.tier })
  }

  @Test
  fun anItemOutOfItsPathIsFoundByItsOwnText() {
    val before = node(FRAME, null, null)
    val content = node(LINEAR, null, before)
    val marker = mark(node(BUTTON, "Send", content))

    val after = node(FRAME, null, null)
    val wrapper = node(FRAME, null, after)
    val moved = node(LINEAR, null, wrapper)
    val send = node(BUTTON, "Send", moved)
    val found = resolve(marker, after)
    assertEquals(send, found.node)
    assertEquals(Tier.SCAN, found.tier)
  }

  @Test
  fun anItemThatIsGoneIsNotFound() {
    val before = node(FRAME, null, null)
    val content = node(LINEAR, null, before)
    val marker = mark(node(BUTTON, "Send", content))
    val after = node(FRAME, null, null)
    node(BUTTON, "Cancel", node(LINEAR, null, after))
    val found = resolve(marker, after)
    assertNull(found.node)
    assertFalse(found.ambiguous)
  }

  @Test
  fun whatIsTypedIsNeverKept() {
    val root = node(FRAME, null, null)
    val password =
      node(EDIT, "secret", root) {
        isPassword = true
        isEditable = true
      }
    assertNull(MarkerText.of(password))
    assertNull(mark(password).text)

    val field =
      node(EDIT, "hello there", root) {
        isEditable = true
        hintText = "Message"
      }
    assertEquals("Message", MarkerText.of(field))
  }

  @Test
  fun anItemComesBeforeTheItemsAfterItAndInIt() {
    val root = node(FRAME, null, null)
    val first = node(LINEAR, null, root)
    val inFirst = node(TEXT, "a", first)
    val second = node(TEXT, "b", root)
    val order = DocumentOrder(listOf(root))
    val positions = listOf(first, inFirst, second).map { order.positionOf(it, Deadline(10_000))!! }
    assertTrue(DocumentOrder.compare(positions[0], positions[1]) < 0)
    assertTrue(DocumentOrder.compare(positions[1], positions[2]) < 0)
    assertNull(DocumentOrder(listOf(first)).positionOf(second, Deadline(10_000)))
    assertEquals(second, order.nearest(listOf(first, second), inFirst, forward = true, Deadline(10_000)) { it })
    assertEquals(first, order.nearest(listOf(first, second), inFirst, forward = false, Deadline(10_000)) { it })
  }

  private fun list(parent: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat =
    node("androidx.recyclerview.widget.RecyclerView", null, parent) {
      isScrollable = true
      addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
      addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
      collectionInfo = AccessibilityNodeInfo.CollectionInfo(50, 1, false)
    }

  private fun row(list: AccessibilityNodeInfoCompat, text: String?, index: Int): AccessibilityNodeInfoCompat =
    node(TEXT, text, list) {
      collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo(index, 1, 0, 1, false)
    }

  /** Makes a node sealed, as the ones an app sends are, so that reading its parent works. */
  private fun node(
    className: String,
    text: String?,
    parent: AccessibilityNodeInfoCompat?,
    setUp: AccessibilityNodeInfo.() -> Unit = {},
  ): AccessibilityNodeInfoCompat {
    // A node of a view of its own, which it equals and no other node does.
    val raw = AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication()))
    raw.className = className
    raw.text = text
    raw.isVisibleToUser = true
    raw.setBoundsInScreen(Rect(0, 0, 100, 100))
    raw.setUp()
    ReflectionHelpers.callInstanceMethod<Unit>(
      raw,
      "setSealed",
      ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
    )
    if (parent != null) shadowOf(parent.unwrap()).addChild(raw)
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  private companion object {
    const val FRAME = "android.widget.FrameLayout"
    const val LINEAR = "android.widget.LinearLayout"
    const val TEXT = "android.widget.TextView"
    const val BUTTON = "android.widget.Button"
    const val EDIT = "android.widget.EditText"
  }
}
