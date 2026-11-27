package com.github.andreyasadchy.xtra.ui.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

/**
 * Mobile-only accent & subtle ambient glow helper.
 * Independent from Dark / AMOLED / Light themes and strictly no-op on Android TV
 * (TV uses its own `TvAmbient` / `TvAccent` preferences).
 */
object MobileAccent {

    private val COLORS = linkedMapOf(
        "violet" to 0xFF9147FF.toInt(),
        "blue" to 0xFF3B82F6.toInt(),
        "cyan" to 0xFF06B6D4.toInt(),
        "green" to 0xFF22C55E.toInt(),
        "orange" to 0xFFF97316.toInt(),
        "red" to 0xFFEF4444.toInt(),
        "pink" to 0xFFEC4899.toInt(),
        "sky" to 0xFF38BDF8.toInt(),
        "turquoise" to 0xFF2DD4BF.toInt(),
        "mint" to 0xFF34D399.toInt(),
        "amber" to 0xFFF59E0B.toInt(),
        "coral" to 0xFFFB7185.toInt(),
        "lavender" to 0xFFA78BFA.toInt(),
    )

    fun colorOf(key: String?): Int? = COLORS[key]

    fun color(context: Context): Int? {
        if (TvUi.isTv(context)) return null
        val key = context.prefs().getString(C.MOBILE_ACCENT_COLOR, "violet") ?: "violet"
        return COLORS[key]
    }

    fun isLightTheme(context: Context): Boolean {
        val prefs = context.prefs()
        val theme = if (prefs.getBoolean(C.UI_THEME_FOLLOW_SYSTEM, false)) {
            when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> prefs.getString(C.UI_THEME_DARK_ON, "0") ?: "0"
                else -> prefs.getString(C.UI_THEME_DARK_OFF, "2") ?: "2"
            }
        } else {
            prefs.getString(C.THEME, "0") ?: "0"
        }
        return theme == "2" || theme == "5"
    }

    /** Contrast-tuned color for streamer names and highlights on mobile cards. */
    fun streamerNameColor(context: Context): Int? {
        val base = color(context) ?: return null
        return if (isLightTheme(context)) {
            ColorUtils.blendARGB(base, Color.BLACK, 0.28f)
        } else {
            ColorUtils.blendARGB(base, Color.WHITE, 0.26f)
        }
    }

    /** Applies the chosen mobile accent color to a streamer name TextView (strictly no-op on TV). */
    fun applyStreamerNameTint(view: TextView) {
        val context = view.context
        if (TvUi.isTv(context)) return
        val accent = streamerNameColor(context)
        if (accent != null) {
            view.setTextColor(accent)
        } else {
            val fallback = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                Color.WHITE,
            )
            view.setTextColor(fallback)
        }
    }

    /** Tints the mobile BottomNavigationView / NavigationRailView active pill, icon, and label (strictly no-op on TV). */
    fun applyBottomNavTint(navView: com.google.android.material.navigation.NavigationBarView) {
        val context = navView.context
        if (TvUi.isTv(context)) return
        val accent = color(context) ?: return
        val inactive = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xB3FFFFFF.toInt(),
        )
        val activeContent = if (isLightTheme(context)) {
            ColorUtils.blendARGB(accent, Color.BLACK, 0.18f)
        } else {
            ColorUtils.blendARGB(accent, Color.WHITE, 0.22f)
        }
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        val colors = intArrayOf(activeContent, inactive)
        val csl = ColorStateList(states, colors)
        navView.itemIconTintList = csl
        navView.itemTextColor = csl
        navView.itemActiveIndicatorColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(accent, if (isLightTheme(context)) 46 else 56),
        )
    }

    /** Tints a mobile TabLayout indicator and selected tab text (strictly no-op on TV). */
    fun applyTabLayoutTint(tabs: TabLayout) {
        val context = tabs.context
        if (TvUi.isTv(context)) return
        val accent = color(context) ?: return
        val inactive = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xB3FFFFFF.toInt(),
        )
        val active = if (isLightTheme(context)) {
            ColorUtils.blendARGB(accent, Color.BLACK, 0.20f)
        } else {
            ColorUtils.blendARGB(accent, Color.WHITE, 0.22f)
        }
        tabs.setSelectedTabIndicatorColor(accent)
        tabs.setTabTextColors(inactive, active)
    }

    /**
     * Applies a subtle top ambient glow over the theme background on mobile dark/AMOLED screens.
     * Leaves the lower 70%+ untouched so AMOLED stays pure black. Strictly no-op on TV and Light theme.
     */
    fun applyScreenAmbient(view: View) {
        val context = view.context
        if (TvUi.isTv(context)) return
        val baseBg = MaterialColors.getColor(context, android.R.attr.colorBackground, 0xFF121212.toInt())
        val accent = color(context)
        if (accent == null || isLightTheme(context)) {
            view.background = ColorDrawable(baseBg)
            return
        }
        val topGlow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, 34),
                ColorUtils.setAlphaComponent(accent, 10),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            ),
        )
        view.background = LayerDrawable(arrayOf(ColorDrawable(baseBg), topGlow))
    }

    /**
     * Tints active switches and category headers inside mobile Preference screens using the chosen accent color.
     * Strictly no-op on Android TV or when accent color is set to "none".
     */
    fun applyPreferenceListAccent(listView: androidx.recyclerview.widget.RecyclerView) {
        val context = listView.context
        if (TvUi.isTv(context)) return
        val accent = color(context) ?: return
        val light = isLightTheme(context)
        val headerColor = streamerNameColor(context) ?: accent

        val trackChecked = accent
        val trackUnchecked = if (light) 0xFFD4D4DC.toInt() else 0xFF33333D.toInt()
        val trackDisabledChecked = ColorUtils.setAlphaComponent(accent, 96)
        val trackDisabledUnchecked = if (light) 0xFFE5E5EC.toInt() else 0xFF24242C.toInt()

        val thumbChecked = if (ColorUtils.calculateLuminance(accent) > 0.58) 0xFF14121C.toInt() else Color.WHITE
        val thumbUnchecked = if (light) 0xFF747480.toInt() else 0xFF9E9EAB.toInt()
        val thumbDisabled = if (light) 0xFFB0B0BC.toInt() else 0xFF5A5A66.toInt()

        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
        val trackCsl = ColorStateList(
            states,
            intArrayOf(trackDisabledChecked, trackDisabledUnchecked, trackChecked, trackUnchecked),
        )
        val thumbCsl = ColorStateList(
            states,
            intArrayOf(thumbDisabled, thumbDisabled, thumbChecked, thumbUnchecked),
        )
        val outlineUnchecked = if (light) 0xFF8C8C98.toInt() else 0xFF6B6B78.toInt()
        val decorCsl = ColorStateList(
            states,
            intArrayOf(Color.TRANSPARENT, outlineUnchecked, Color.TRANSPARENT, outlineUnchecked),
        )

        fun tintRow(row: View) {
            fun walk(node: View) {
                if (node is androidx.appcompat.widget.SwitchCompat) {
                    node.trackTintList = trackCsl
                    node.thumbTintList = thumbCsl
                    if (node is com.google.android.material.materialswitch.MaterialSwitch) {
                        node.trackDecorationTintList = decorCsl
                    }
                    return
                }
                if (node is android.view.ViewGroup) {
                    for (i in 0 until node.childCount) walk(node.getChildAt(i))
                }
            }
            walk(row)
            // PreferenceCategory headers are non-clickable rows without a widget_frame
            if (!row.isClickable && row.findViewById<View>(android.R.id.widget_frame) == null) {
                row.findViewById<TextView>(android.R.id.title)?.setTextColor(headerColor)
            }
        }

        for (i in 0 until listView.childCount) {
            tintRow(listView.getChildAt(i))
        }
        listView.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    tintRow(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
    }

    /** Visual color picker dialog with color dots for Paramètres -> Thème -> Couleur (mobile-only). */
    fun showColorPickerDialog(
        context: Context,
        title: CharSequence,
        entries: Array<CharSequence>,
        values: Array<CharSequence>,
        currentValue: String,
        onSelected: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val dotSize = (18 * density).toInt()
        val pad = (10 * density).toInt()
        val selectedIndex = values.indexOfFirst { it.toString() == currentValue }.coerceAtLeast(0)

        val adapter = object : ArrayAdapter<CharSequence>(
            context,
            android.R.layout.select_dialog_singlechoice,
            entries,
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1) ?: (view as? TextView)
                if (tv != null) {
                    val key = values.getOrNull(position)?.toString()
                    val c = colorOf(key)
                    val dot = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setSize(dotSize, dotSize)
                        if (c != null) {
                            setColor(c)
                        } else {
                            setColor(Color.TRANSPARENT)
                            setStroke((2 * density).toInt().coerceAtLeast(2), 0x88FFFFFF.toInt())
                        }
                    }
                    dot.setBounds(0, 0, dotSize, dotSize)
                    tv.setCompoundDrawablesRelative(dot, null, null, null)
                    tv.compoundDrawablePadding = pad
                }
                return view
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(adapter, selectedIndex) { dialog, which ->
                val chosen = values.getOrNull(which)?.toString() ?: return@setSingleChoiceItems
                dialog.dismiss()
                onSelected(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
