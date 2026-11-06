package com.macrophage.barspeed.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * [action] with a buzz first, for the taps a lifter makes without looking:
 * +1 REP, START SET, START NEXT SET, END SET EARLY and the failure button
 * (UX review 2026-11-06, accessibility F3). Without it a chalky thumb could
 * not tell whether a tap registered unless he looked at the count.
 *
 * CONFIRM from API 30, LONG_PRESS before it; both obey the phone's own
 * touch-feedback setting. Wired as `onClick = confirmTap(viewModel::beginSet)`.
 * Compile- and lint-gated only: no test can feel a vibration.
 */
@Composable
fun confirmTap(action: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            },
        )
        action()
    }
}
