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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenKeyTest {

  @Test
  fun aPageIsTheSameWithOrWithoutItsSchemeAndFragment() {
    val page = ScreenKey.pageOf("https://www.Example.com/news/today#top")
    assertEquals(page, ScreenKey.pageOf("example.com/news/today"))
    assertEquals(page, ScreenKey.pageOf("http://example.com/news/today/"))
    assertNotEquals(page, ScreenKey.pageOf("example.com/news/yesterday"))
    assertNotEquals(page, ScreenKey.pageOf("example.com/News/today"))
    assertNull(ScreenKey.pageOf("  "))
  }

  @Test
  fun onlyAHashOfTheAddressIsKept() {
    val key = ScreenKey.of("com.android.chrome", "Main", "example.com/secret")
    assertEquals(16, key.page!!.length)
    assertFalse(key.page!!.contains("secret"))
  }

  @Test
  fun markersOfAnUnknownActivityOrPageMayBeShown() {
    val saved = ScreenKey("app", "Main", "page")
    assertTrue(ScreenKey("app", "Main", "page").mayShow(saved))
    assertTrue(ScreenKey("app", null, null).mayShow(saved))
    assertTrue(ScreenKey("app", "Main", null).mayShow(saved))
    assertTrue(ScreenKey("app", "Main", "other").mayShow(ScreenKey("app", "Main", null)))
    assertFalse(ScreenKey("app", "Main", "other").mayShow(saved))
    assertFalse(ScreenKey("app", "Settings", "page").mayShow(saved))
    assertFalse(ScreenKey("other", "Main", "page").mayShow(saved))
  }
}
