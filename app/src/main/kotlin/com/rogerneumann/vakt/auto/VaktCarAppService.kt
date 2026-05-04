package com.rogerneumann.vakt.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.rogerneumann.vakt.auto.screens.DashboardScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point for the Android Auto Car App Library (Full-screen dashboard).
 */
@AndroidEntryPoint
class VaktCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allow all hosts (standard for development/sideloading)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return VaktSession()
    }
}

class VaktSession : Session() {
    override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
        return DashboardScreen(carContext)
    }
}
