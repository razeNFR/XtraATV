package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class Stream(
    var id: String? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    var channelImageURL: String? = null,
    var gameId: String? = null,
    var gameSlug: String? = null,
    var gameName: String? = null,
    var title: String? = null,
    val thumbnailURL: String? = null,
    var createdAt: String? = null,
    var viewerCount: Int? = null,
    val tags: List<String>? = null,
    val language: String? = null,
) : Parcelable {

    val channelImage: String?
        get() = TwitchApiHelper.getProfileImage(channelImageURL)
    val thumbnail: String?
        get() = TwitchApiHelper.getStreamThumbnail(thumbnailURL)

    /** Short uppercase ISO language code for thumbnail badges (e.g. "FR", "EN", "DE"), or null if unset/"other". */
    val displayLanguageCode: String?
        get() {
            val raw = language?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty() || raw == "other" || raw == "asl") return if (raw == "asl") "ASL" else null
            val primary = raw.substringBefore('-').substringBefore('_')
            return primary.takeIf { it.length in 2..3 }?.uppercase()
        }
}
