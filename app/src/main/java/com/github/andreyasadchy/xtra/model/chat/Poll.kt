package com.github.andreyasadchy.xtra.model.chat

class Poll(
    val id: String?,
    val title: String?,
    val status: String?,
    val choices: List<PollChoice>?,
    val totalVotes: Int?,
    val remainingMilliseconds: Int?,
    val pointsVotingEnabled: Boolean = false,
    val pointsPerVote: Int = 0,
) {
    class PollChoice(
        val id: String? = null,
        val title: String?,
        val totalVotes: Int?,
    )
}
