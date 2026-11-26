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
import com.google.android.accessibility.talkback.commands.Commands

/**
 * The place marker commands, which a gesture, a keyboard shortcut, a menu, a macro or a voice
 * command can run. Each is saved as [PREFIX] and its name.
 */
object PlaceMarkerCommands {
  /** Starts every saved value of these commands. It must never change once released. */
  private const val PREFIX = "PLACE_MARKER_"

  /** The numbers markers have on a screen: 1 to this. */
  const val COUNT = 10

  /** What a place marker command does. */
  sealed interface Command

  /** Moves to the marker with [number] on the screen. */
  data class GoTo(val number: Int) : Command

  /** Sets the marker with [number] on the focused item. */
  data class SetNumber(val number: Int) : Command

  /** Moves to the next marker on the screen. */
  data object Next : Command

  /** Moves to the previous marker on the screen. */
  data object Previous : Command

  /** Moves back to where focus was before it jumped to a marker. */
  data object LastSpot : Command

  /** Sets the first free marker on the focused item, or the one used longest ago. */
  data object SetAny : Command

  /** Deletes the marker of the focused item. */
  data object Delete : Command

  /** Deletes every marker of the screen. */
  data object DeleteAll : Command

  /** Opens the list of the markers of every screen. */
  data object Manage : Command

  /** Returns the value [command] is saved as. */
  fun valueOf(command: Command): String =
    PREFIX +
      when (command) {
        is GoTo -> "GO_${command.number}"
        is SetNumber -> "SET_${command.number}"
        Next -> "NEXT"
        Previous -> "PREVIOUS"
        LastSpot -> "LAST_SPOT"
        SetAny -> "SET"
        Delete -> "DELETE"
        DeleteAll -> "DELETE_ALL"
        Manage -> "MANAGE"
      }

  /** Every command, in the order of the command list. */
  fun all(): List<Command> =
    listOf(Next, Previous, LastSpot, SetAny, Delete, DeleteAll, Manage) +
      (1..COUNT).map(::GoTo) +
      (1..COUNT).map(::SetNumber)

  private val byValue: Map<String, Command> by lazy { all().associateBy(::valueOf) }

  /** Returns whether [value] is one of these commands. */
  @JvmStatic fun isCommand(value: String?): Boolean = value != null && value in byValue

  /** Returns the command saved as [value], or null when it isn't one of these. */
  @JvmStatic fun parse(value: String?): Command? = value?.let(byValue::get)

  /** Returns the name of [command]. */
  fun label(context: Context, command: Command): String =
    when (command) {
      is GoTo -> context.getString(R.string.shortcut_go_to_place_marker, command.number)
      is SetNumber -> context.getString(R.string.shortcut_set_place_marker_number, command.number)
      Next -> context.getString(R.string.shortcut_next_place_marker)
      Previous -> context.getString(R.string.shortcut_previous_place_marker)
      LastSpot -> context.getString(R.string.shortcut_go_to_last_spot)
      SetAny -> context.getString(R.string.shortcut_set_place_marker)
      Delete -> context.getString(R.string.shortcut_delete_place_marker)
      DeleteAll -> context.getString(R.string.shortcut_delete_screen_place_markers)
      Manage -> context.getString(R.string.shortcut_manage_place_markers)
    }

  /** The commands of the Place markers category. */
  fun commands(context: Context): List<Commands.Command> =
    all().map { Commands.Command(valueOf(it), label(context, it), keyboardAction = null) }
}
