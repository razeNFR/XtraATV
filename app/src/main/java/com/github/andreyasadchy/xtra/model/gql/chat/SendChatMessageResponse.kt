package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class SendChatMessageResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val sendChatMessage: Payload? = null,
    )

    @Serializable
    class Payload(
        val dropReason: String? = null,
        val dropReasonMessage: String? = null,
    )
}
