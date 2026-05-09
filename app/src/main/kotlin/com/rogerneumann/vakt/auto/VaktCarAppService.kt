package com.rogerneumann.vakt.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import com.rogerneumann.vakt.auto.screens.DashboardScreen
import com.rogerneumann.vakt.data.OBD2Repository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Entry point for the Android Auto Car App Library (Full-screen dashboard).
 */
@AndroidEntryPoint
class VaktCarAppService : CarAppService() {

    @Inject lateinit var repository: OBD2Repository

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return VaktSession(repository)
    }
}

class VaktSession(
    private val repository: OBD2Repository
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return DashboardScreen(carContext, repository)
    }
}
