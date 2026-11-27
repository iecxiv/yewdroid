package iecxiv.yewdroid

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Size of the video shown inline in the terminal and in the expanded player. It keeps the video's proportions
 * and never takes more than a third of the screen height, so on small screens or with a large font or display
 * size the controls and the list below stay in view. In portrait it fills the width until that limit is reached;
 * in landscape it is 150 dp high, or less when a third of the screen is smaller than that. Fullscreen is not
 * affected.
 */
@Composable
internal fun videoBoxModifier(landscape: Boolean, ratio: Float): Modifier {
    val third = (LocalConfiguration.current.screenHeightDp / 3).dp
    return if (landscape) {
        Modifier.height(minOf(150.dp, third)).aspectRatio(ratio)
    } else {
        Modifier.heightIn(max = third).aspectRatio(ratio)
    }
}
