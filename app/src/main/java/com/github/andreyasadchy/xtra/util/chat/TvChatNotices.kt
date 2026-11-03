package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.appcompat.content.res.AppCompatResources
import com.github.andreyasadchy.xtra.R
import java.text.NumberFormat

/**
 * Chat: the notices of Twitch (subscriptions, gifts, raids, announcements...) in a short line of our own, translated,
 * instead of the English sentence Twitch writes in them. Unknown kinds keep the sentence of Twitch.
 */
object TvChatNotices {

    const val SIZE = 0.82f

    /** Text and icon of the notice of [tags] (a USERNOTICE of Twitch), null for a kind not handled here. */
    fun build(context: Context, tags: Map<String, String?>): Pair<String, Int>? {
        val name = tags["display-name"]?.takeIf { it.isNotBlank() } ?: tags["login"] ?: return null
        val plan = when (tags["msg-param-sub-plan"]) {
            "Prime" -> context.getString(R.string.tv_chat_sub_prime)
            "2000" -> context.getString(R.string.tv_chat_sub_tier, 2)
            "3000" -> context.getString(R.string.tv_chat_sub_tier, 3)
            else -> null
        }
        fun withPlan(text: String) = if (plan != null) "$text · $plan" else text
        fun count(key: String) = tags[key]?.toIntOrNull()
        return when (tags["msg-id"]) {
            "sub" -> withPlan(context.getString(R.string.tv_chat_sub, name)) to R.drawable.tv_ic_star
            "resub" -> {
                val months = count("msg-param-cumulative-months")
                val text = if (months != null && months > 1) {
                    context.getString(R.string.tv_chat_resub, name) + " · " + context.getString(R.string.tv_chat_months, months)
                } else {
                    context.getString(R.string.tv_chat_sub, name)
                }
                withPlan(text) to R.drawable.tv_ic_star
            }
            "subgift", "anonsubgift" -> {
                val recipient = tags["msg-param-recipient-display-name"] ?: tags["msg-param-recipient-user-name"] ?: return null
                context.getString(R.string.tv_chat_sub_gift, giver(context, tags, name), recipient) to R.drawable.tv_ic_gift
            }
            "submysterygift", "anonsubmysterygift" -> {
                val gifts = count("msg-param-mass-gift-count") ?: return null
                (if (gifts == 1) context.getString(R.string.tv_chat_sub_gifts_one, giver(context, tags, name)) else context.getString(R.string.tv_chat_sub_gifts, giver(context, tags, name), gifts)) to R.drawable.tv_ic_gift
            }
            "giftpaidupgrade", "anongiftpaidupgrade", "primepaidupgrade" ->
                context.getString(R.string.tv_chat_sub_continue, name) to R.drawable.tv_ic_star
            "raid" -> {
                val raider = tags["msg-param-displayName"] ?: tags["msg-param-login"] ?: name
                val viewers = count("msg-param-viewerCount")
                val text = context.getString(R.string.tv_chat_raid, raider)
                (if (viewers != null) text + " · " + context.getString(R.string.tv_chat_viewers, NumberFormat.getInstance().format(viewers)) else text) to R.drawable.tv_ic_raid
            }
            "announcement" -> context.getString(R.string.tv_chat_announcement) to R.drawable.tv_ic_campaign
            "bitsbadgetier" -> context.getString(R.string.tv_chat_bits_badge, name) to R.drawable.tv_ic_diamond
            "viewermilestone" -> {
                val streams = count("msg-param-value")
                if (tags["msg-param-category"] == "watch-streak" && streams != null) {
                    context.getString(R.string.tv_chat_watch_streak, name, streams) to R.drawable.tv_ic_eye
                } else null
            }
            else -> null
        }
    }

    /** The same for a notice of EventSub ("channel.chat.notification"), its fields given as the tags of Twitch chat. */
    fun build(context: Context, json: org.json.JSONObject): Pair<String, Int>? {
        fun str(obj: org.json.JSONObject?, key: String) = obj?.takeIf { !it.isNull(key) }?.optString(key)?.takeIf { it.isNotBlank() }
        val type = str(json, "notice_type") ?: return null
        val anonymous = json.optBoolean("chatter_is_anonymous")
        val tags = HashMap<String, String?>()
        tags["display-name"] = str(json, "chatter_user_name")
        tags["login"] = if (anonymous) "ananonymousgifter" else str(json, "chatter_user_login")
        fun plan(obj: org.json.JSONObject?) {
            tags["msg-param-sub-plan"] = if (obj?.optBoolean("is_prime") == true) "Prime" else str(obj, "sub_tier")
        }
        when (type) {
            "sub" -> { tags["msg-id"] = "sub"; plan(json.optJSONObject("sub")) }
            "resub" -> {
                val obj = json.optJSONObject("resub")
                tags["msg-id"] = "resub"; plan(obj)
                tags["msg-param-cumulative-months"] = obj?.optInt("cumulative_months")?.toString()
            }
            "sub_gift" -> {
                val obj = json.optJSONObject("sub_gift")
                tags["msg-id"] = "subgift"
                tags["msg-param-recipient-display-name"] = str(obj, "recipient_user_name")
            }
            "community_sub_gift" -> {
                tags["msg-id"] = "submysterygift"
                tags["msg-param-mass-gift-count"] = json.optJSONObject("community_sub_gift")?.optInt("total")?.toString()
            }
            "gift_paid_upgrade", "prime_paid_upgrade" -> tags["msg-id"] = "giftpaidupgrade"
            "raid" -> {
                val obj = json.optJSONObject("raid")
                tags["msg-id"] = "raid"
                tags["msg-param-displayName"] = str(obj, "user_name")
                tags["msg-param-viewerCount"] = obj?.optInt("viewer_count")?.toString()
            }
            "announcement" -> tags["msg-id"] = "announcement"
            "bits_badge_tier" -> tags["msg-id"] = "bitsbadgetier"
            else -> return null
        }
        return build(context, tags)
    }

    private fun giver(context: Context, tags: Map<String, String?>, name: String): String {
        return if (tags["login"] == "ananonymousgifter" || tags["msg-id"]?.startsWith("anon") == true) context.getString(R.string.tv_chat_anonymous) else name
    }

    /** Appends the icon [res] at the size of the text of the notice, centered on the line. */
    fun appendIcon(context: Context, builder: SpannableStringBuilder, res: Int, color: Int, textSizePx: Float) {
        val drawable = AppCompatResources.getDrawable(context, res)?.mutate() ?: return
        drawable.setTint(color)
        val size = (textSizePx * SIZE * 1.15f).toInt()
        drawable.setBounds(0, 0, size, size)
        val start = builder.length
        builder.append("￼ ")
        builder.setSpan(CenteredIconSpan(drawable), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Background of an answer to the user or a mention of the user: the accent color over it and a bar of it on its left. */
    fun highlight(context: Context): Drawable {
        val color = com.github.andreyasadchy.xtra.ui.tv.TvAccent.color
        val bar = 3f * context.resources.displayMetrics.density
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun draw(canvas: Canvas) {
                paint.color = (color and 0x00FFFFFF) or 0x40000000
                canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
                paint.color = color or 0xFF000000.toInt()
                canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + bar, bounds.bottom.toFloat(), paint)
            }

            override fun setAlpha(alpha: Int) {}

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /** An icon in a line of text, centered on the text (ImageSpan centers only from Android 10). */
    private class CenteredIconSpan(private val drawable: Drawable) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            return drawable.bounds.width()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val metrics = paint.fontMetricsInt
            val center = y + (metrics.descent + metrics.ascent) / 2f
            canvas.save()
            canvas.translate(x, center - drawable.bounds.height() / 2f)
            drawable.draw(canvas)
            canvas.restore()
        }
    }
}
