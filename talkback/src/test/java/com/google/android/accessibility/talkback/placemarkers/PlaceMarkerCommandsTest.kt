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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMarkerCommandsTest {

  @Test
  fun everyCommandIsFoundAgainByItsValue() {
    val all = PlaceMarkerCommands.all()
    assertEquals(27, all.size)
    assertEquals(all.size, all.map(PlaceMarkerCommands::valueOf).toSet().size)
    for (command in all) {
      assertEquals(command, PlaceMarkerCommands.parse(PlaceMarkerCommands.valueOf(command)))
    }
  }

  @Test
  fun savedValuesStayTheSame() {
    assertEquals("PLACE_MARKER_GO_3", PlaceMarkerCommands.valueOf(PlaceMarkerCommands.GoTo(3)))
    assertEquals("PLACE_MARKER_SET_10", PlaceMarkerCommands.valueOf(PlaceMarkerCommands.SetNumber(10)))
    assertEquals("PLACE_MARKER_SET", PlaceMarkerCommands.valueOf(PlaceMarkerCommands.SetAny))
    assertEquals("PLACE_MARKER_DELETE_ALL", PlaceMarkerCommands.valueOf(PlaceMarkerCommands.DeleteAll))
    assertEquals("PLACE_MARKER_MANAGE", PlaceMarkerCommands.valueOf(PlaceMarkerCommands.Manage))
    assertEquals("PLACE_MARKER_LAST_SPOT", PlaceMarkerCommands.valueOf(PlaceMarkerCommands.LastSpot))
  }

  @Test
  fun otherValuesAreNotCommands() {
    for (value in listOf(null, "", "NEXT", "PLACE_MARKER_", "PLACE_MARKER_GO_0", "PLACE_MARKER_GO_11",
        "PLACE_MARKER_GO_01", "PLACE_MARKER_SET_x", "PLACE_MARKER_JUMP")) {
      assertNull(value, PlaceMarkerCommands.parse(value))
      assertFalse(PlaceMarkerCommands.isCommand(value))
    }
    assertTrue(PlaceMarkerCommands.isCommand("PLACE_MARKER_NEXT"))
  }
}
