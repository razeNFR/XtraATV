package com.github.andreyasadchy.xtra.model.gql.channel

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.util.C
import kotlinx.serialization.Serializable

@Serializable
class FollowUserResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val followUser: Payload? = null,
    )

    @Serializable
    class Payload(
        val follow: Follow? = null,
        val error: MutationError? = null,
    )

    @Serializable
    class Follow(
        val disableNotifications: Boolean? = null,
        val user: TargetUser? = null,
    )

    @Serializable
    class TargetUser(
        val id: String? = null,
    )

    @Serializable
    class MutationError(
        val code: String? = null,
    )

    /**
     * Returns null when Twitch actually recorded the follow, or an error string / sentinel
     * (`FOLLOW_REJECTED:<code>`) when Twitch rejected the follow (e.g. Follower Verification requiring
     * a verified phone number or email on Twitch, or channel block).
     */
    fun extractErrorOrRejection(): String? {
        errors?.firstOrNull()?.message?.takeIf { it.isNotBlank() }?.let { return it }
        val errCode = data?.followUser?.error?.code?.takeIf { it.isNotBlank() }
        if (errCode != null) {
            return "${C.FOLLOW_REJECTED_PREFIX}$errCode"
        }
        if (data?.followUser?.follow == null) {
            return "${C.FOLLOW_REJECTED_PREFIX}REJECTED"
        }
        return null
    }
}
