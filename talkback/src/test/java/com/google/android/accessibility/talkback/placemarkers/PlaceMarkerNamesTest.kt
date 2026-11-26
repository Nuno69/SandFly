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
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceMarkerNamesTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun screen(activity: String?, page: String? = null, title: String? = null) =
    StoredScreen(ScreenKey("app", activity, page), 1, emptyList(), title)

  private fun marker(text: String?, role: Int) =
    StoredMarker(1, 1, null, role, text, null, null, false, emptyList(), null, null, null)

  @Test
  fun activitiesAreNamedWithoutTheirPackageAndActivity() {
    assertEquals("Conversation", PlaceMarkerNames.activityName("com.whatsapp.Conversation"))
    assertEquals("Launch", PlaceMarkerNames.activityName("org.telegram.ui.LaunchActivity"))
    assertEquals("Settings", PlaceMarkerNames.activityName("com.app.Main\$Settings"))
    assertEquals("Activity", PlaceMarkerNames.activityName("com.app.Activity"))
    assertNull(PlaceMarkerNames.activityName(null))
    assertNull(PlaceMarkerNames.activityName("com.app."))
  }

  @Test
  fun aScreenIsNamedByItsTitleUnlessItIsTheAppsName() {
    assertEquals("Inbox", PlaceMarkerNames.screenName(context, screen("com.app.MainActivity", title = " Inbox\n"), "Mail"))
    assertEquals("Main", PlaceMarkerNames.screenName(context, screen("com.app.MainActivity", title = "mail"), "Mail"))
    assertEquals("Main", PlaceMarkerNames.screenName(context, screen("com.app.MainActivity"), "Mail"))
    assertEquals(
      context.getString(R.string.place_markers_page),
      PlaceMarkerNames.screenName(context, screen("com.browser.Main", page = "0123456789abcdef", title = "Browser"), "Browser"),
    )
    assertEquals("Mail", PlaceMarkerNames.screenName(context, screen(null), "Mail"))
  }

  @Test
  fun aMarkerIsDescribedByItsTextElseItsRole() {
    assertEquals("Send", PlaceMarkerNames.markerSummary(context, marker("Send", Role.ROLE_BUTTON)).toString())
    assertEquals(
      context.getString(R.string.value_button),
      PlaceMarkerNames.markerSummary(context, marker(null, Role.ROLE_BUTTON)).toString(),
    )
    assertEquals(
      context.getString(R.string.place_marker_no_text),
      PlaceMarkerNames.markerSummary(context, marker(null, Role.ROLE_VIEW_GROUP)).toString(),
    )
  }

  @Test
  fun anAppThatIsntInstalledIsNamedByItsPackage() {
    assertEquals("com.example.gone", PlaceMarkerNames.appName(context, "com.example.gone"))
  }
}
