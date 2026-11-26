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

import java.security.MessageDigest

/**
 * The screen markers belong to: an app's [activity], and in a browser its [page]. Either is null
 * when it isn't known, such as an activity before the app's first window change since SandFly
 * started, or a page whose address the browser doesn't show.
 */
data class ScreenKey(val packageName: String, val activity: String?, val page: String?) {

  /**
   * Whether markers saved for [saved] may be on this screen. A part not known here, or not known
   * when the markers were saved, doesn't rule them out; markers of a page are also looked for
   * among those set on the same activity while its page wasn't known.
   */
  fun mayShow(saved: ScreenKey): Boolean =
    saved.packageName == packageName &&
      (activity == null || saved.activity == activity) &&
      (page == null || saved.page == null || saved.page == page)

  companion object {
    private const val HASH_HEX_CHARS = 16

    /**
     * Returns the key of a screen of [packageName] showing [activity], with the [url] of its page
     * in a browser. Only a hash of the address is kept, never the address itself.
     */
    fun of(packageName: String, activity: String?, url: CharSequence?): ScreenKey =
      ScreenKey(packageName, activity, url?.let(::pageOf))

    /**
     * Returns the hash of [url] without its scheme, its "www." and its fragment, which the
     * address bar may show or leave out for the same page.
     */
    fun pageOf(url: CharSequence): String? {
      var page = url.toString().trim()
      page = page.substringBefore('#')
      page = page.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
      page = page.removePrefix("www.").trimEnd('/')
      if (page.isEmpty()) return null
      val host = page.substringBefore('/')
      page = host.lowercase() + page.substring(host.length)
      val digest = MessageDigest.getInstance("SHA-256").digest(page.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_CHARS)
    }
  }
}
