package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Lightweight, zero-blur Twitch channel offline screen overlay shared by the TV main player,
 * Multi-Stream tiles, and PiP windows.
 *
 * Displays the streamer's Twitch offline screen (`offlineImageURL`), or a dark banner/ambient fallback
 * when none is configured, together with their profile picture, display name, and offline status.
 */
object TvOfflineOverlay {

    suspend fun fetchInfo(context: Context, login: String): GraphQLRepository.ChannelOfflineInfo? {
        if (login.isBlank()) return null
        val app = context.applicationContext as? XtraApp ?: return null
        return app.xtraModule.graphQLRepository.fetchChannelOfflineInfo(
            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            headers = TwitchApiHelper.getGQLHeaders(context),
            channelLogin = login,
        )
    }

    fun createView(
        context: Context,
        displayName: String,
        profileImageUrl: String?,
        offlineImageUrl: String?,
        bannerImageUrl: String?,
        compact: Boolean = false,
        showWaitingHint: Boolean = !compact,
    ): FrameLayout {
        val density = context.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()
        val hasOfflineArt = !offlineImageUrl.isNullOrBlank()
        val bgUrl = offlineImageUrl?.takeIf { it.isNotBlank() } ?: bannerImageUrl?.takeIf { it.isNotBlank() }

        return FrameLayout(context).apply {
            isFocusable = false
            isClickable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF151622.toInt(), 0xFF0A0B10.toInt(), 0xFF11121A.toInt()),
            )

            // Full-bleed Twitch offline image (or dimmed channel banner fallback)
            if (bgUrl != null) {
                addView(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = if (hasOfflineArt) 1f else 0.34f
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(bgUrl)
                                .crossfade(250)
                                .target(this)
                                .build()
                        )
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }

            // Bottom gradient scrim so the channel identity pill stays crisp over bright offline artwork
            addView(
                View(context).apply {
                    isFocusable = false
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        if (hasOfflineArt) {
                            intArrayOf(0xCC06070B.toInt(), 0x4D06070B, Color.TRANSPARENT)
                        } else {
                            intArrayOf(0x9906070B.toInt(), 0x3306070B, Color.TRANSPARENT)
                        },
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )

            // Smoked-glass channel identity capsule (profile picture + username + offline status)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (compact) 0xB80B0C10.toInt() else 0xC80B0C10.toInt())
                    setStroke(maxOf(1, dp(1f)), 0x33FFFFFF)
                    cornerRadius = dp(if (compact) 12f else 18f).toFloat()
                }
                val hPad = dp(if (compact) 10f else 18f)
                val vPad = dp(if (compact) 7f else 14f)
                setPadding(hPad, vPad, hPad, vPad)

                val avatarSize = dp(if (compact) 28f else 52f)
                val avatarView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF252632.toInt())
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: Outline) {
                            outline.setOval(0, 0, v.width, v.height)
                        }
                    }
                    clipToOutline = true
                    if (!profileImageUrl.isNullOrBlank()) {
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(profileImageUrl)
                                .crossfade(200)
                                .target(this)
                                .build()
                        )
                    } else {
                        setImageResource(R.drawable.tv_ic_person)
                        setColorFilter(Color.WHITE)
                        val inset = dp(if (compact) 6f else 12f)
                        setPadding(inset, inset, inset, inset)
                    }
                }
                addView(
                    avatarView,
                    LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                        marginEnd = dp(if (compact) 8f else 14f)
                    },
                )

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL

                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        addView(
                            TextView(context).apply {
                                text = displayName
                                setTextColor(Color.WHITE)
                                setTypeface(typeface, Typeface.BOLD)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 20f)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                weight = 1f
                                marginEnd = dp(if (compact) 6f else 10f)
                            },
                        )

                        // OFFLINE pill badge
                        addView(
                            TextView(context).apply {
                                text = context.getString(R.string.offline).uppercase()
                                setTextColor(Color.WHITE)
                                setTypeface(typeface, Typeface.BOLD)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 9.5f else 11f)
                                maxLines = 1
                                background = GradientDrawable().apply {
                                    setColor(0xFF4B5563.toInt())
                                    cornerRadius = dp(5f).toFloat()
                                }
                                setPadding(dp(if (compact) 5f else 7f), dp(2f), dp(if (compact) 5f else 7f), dp(2f))
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                    addView(topRow)

                    if (showWaitingHint) {
                        addView(
                            TextView(context).apply {
                                text = context.getString(R.string.tv_player_offline_waiting)
                                setTextColor(0xCCFFFFFF.toInt())
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(3f)
                            },
                        )
                    }
                }
                addView(
                    textCol,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

            val cardGravity = when {
                compact -> Gravity.CENTER
                hasOfflineArt -> Gravity.BOTTOM or Gravity.START
                else -> Gravity.CENTER
            }
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    cardGravity,
                ).apply {
                    if (compact) {
                        val m = dp(10f)
                        setMargins(m, m, m, m)
                    } else if (hasOfflineArt) {
                        marginStart = dp(28f)
                        bottomMargin = dp(28f)
                        marginEnd = dp(28f)
                    } else {
                        val m = dp(28f)
                        setMargins(m, m, m, m)
                    }
                },
            )
        }
    }
}
