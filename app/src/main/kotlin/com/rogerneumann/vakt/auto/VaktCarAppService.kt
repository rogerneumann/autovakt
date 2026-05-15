package com.rogerneumann.vakt.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import com.rogerneumann.vakt.auto.screens.DashboardScreen
import com.rogerneumann.vakt.data.LightingManager
import com.rogerneumann.vakt.data.OBD2Repository
import com.rogerneumann.vakt.data.VehicleLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VaktCarAppService : CarAppService() {

    @Inject lateinit var repository: OBD2Repository
    @Inject lateinit var lightingManager: LightingManager
    @Inject lateinit var vehicleLayoutManager: VehicleLayoutManager

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return VaktSession(repository, lightingManager, vehicleLayoutManager)
    }
}

class VaktSession(
    private val repository: OBD2Repository,
    private val lightingManager: LightingManager,
    private val vehicleLayoutManager: VehicleLayoutManager
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return DashboardScreen(carContext, repository, lightingManager, vehicleLayoutManager)
    }
}
