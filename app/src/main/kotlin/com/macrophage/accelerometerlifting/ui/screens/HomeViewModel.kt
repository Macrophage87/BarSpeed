package com.macrophage.accelerometerlifting.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.accelerometerlifting.LiftingApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    val sessions =
        container.sessionRepository.sessions.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    val imuState = container.autoConnect.imuState
    val hrmState = container.autoConnect.hrmState
}
