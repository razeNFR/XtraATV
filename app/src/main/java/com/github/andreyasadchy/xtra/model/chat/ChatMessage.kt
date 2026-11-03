package com.github.andreyasadchy.xtra.model.chat

class ChatMessage(
    val type: Int = SYSTEM_MESSAGE,
    val id: String? = null,
    val userId: String? = null,
    val userLogin: String? = null,
    val userName: String? = null,
    val message: String? = null,
    val color: String? = null,
    val emotes: List<TwitchEmote>? = null,
    val badges: List<Badge>? = null,
    val gif: String? = null,
    val isAction: Boolean = false,
    val isFirst: Boolean = false,
    val bits: Int? = null,
    val systemMsg: String? = null,
    val msgId: String? = null,
    val targetMsgId: String? = null,
    val reward: ChannelPointReward? = null,
    val reply: Reply? = null,
    val replyParent: ChatMessage? = null,
    val timestamp: Long? = null,
    val fullMsg: String? = null,
    var translatedMessage: String? = null,
    var translationFailed: Boolean = false,
    var messageLanguage: String? = null,
    /** Icon of a short notice line (subscription, gift, raid, timeout...): the line is smaller and grey, with this icon. */
    val noticeIcon: Int? = null,
) {
    /** Deleted by a moderator (or its author timed out / banned): shown grey and struck through, or hidden. */
    @Volatile
    var deleted: Boolean = false

    companion object {
        const val SYSTEM_MESSAGE = 0
        const val USER_MESSAGE = 1
        const val REPLY_MESSAGE = 2
        const val NOTICE_MESSAGE = 3
    }
}