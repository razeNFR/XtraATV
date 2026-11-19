package com.github.andreyasadchy.xtra.ui.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity

/**
 * Integrated Statistics root destination inside [MainActivity]'s NavHostFragment on TV.
 * Does not steal focus while the side rail is open (supports focus-switch hover preview).
 */
class TvStatsFragment : Fragment() {

    private val focusTrigger = mutableIntStateOf(0)
    private val refreshStamp = mutableIntStateOf(0)

    fun requestContentFocus() {
        focusTrigger.intValue++
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val act = activity as? MainActivity
        if (!isHidden && act?.isTvMenuOpen() != true && act?.isTvPlayerOverContent() != true) {
            focusTrigger.intValue++
        }
        val accent = com.google.android.material.color.MaterialColors.getColor(
            context,
            androidx.appcompat.R.attr.colorPrimary,
            android.graphics.Color.rgb(145, 71, 255),
        )
        return ComposeView(context).apply {
            id = R.id.tvStatsRoot
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TvTheme(accent) {
                    TvStatsPage.Screen(
                        isEmbeddedPage = true,
                        focusTrigger = focusTrigger.intValue,
                        refreshStamp = refreshStamp.intValue,
                        onOpenSideMenu = { (activity as? MainActivity)?.openTvMenu() },
                        onReset = { refreshStamp.intValue++ },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStamp.intValue++
    }
}
