package com.macrophage.barspeed.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.macrophage.barspeed.model.BlePermissionStep
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens need in order to draw the permission state and act on it.
 *
 * MainActivity supplies it: [step] is the process-scoped gate's flow, and both
 * actions need an Activity, which nothing below this may hold on to.
 */
class PermissionUi(
    val step: StateFlow<BlePermissionStep>,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/**
 * No default value. A screen drawn without a provider is a wiring bug, and the
 * defect this whole change is about is a permission problem rendering as
 * nothing at all -- so absence fails loudly here rather than defaulting to
 * something that draws an empty banner and looks fine.
 */
val LocalBlePermissionUi =
    staticCompositionLocalOf<PermissionUi> { error("no PermissionUi provided; see MainActivity.onCreate") }
