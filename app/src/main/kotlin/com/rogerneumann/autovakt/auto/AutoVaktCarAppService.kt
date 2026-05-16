package com.rogerneumann.autovakt.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import com.rogerneumann.autovakt.auto.screens.DashboardScreen
import com.rogerneumann.autovakt.data.LightingManager
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.media.MediaRemoteManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AutoVaktCarAppService : CarAppService() {

    @Inject lateinit var repository: OBD2Repository
    @Inject lateinit var lightingManager: LightingManager
    @Inject lateinit var vehicleLayoutManager: VehicleLayoutManager
    @Inject lateinit var mediaRemoteManager: MediaRemoteManager

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return AutoVaktSession(repository, lightingManager, vehicleLayoutManager, mediaRemoteManager)
    }
}

class AutoVaktSession(
    private val repository: OBD2Repository,
    private val lightingManager: LightingManager,
    private val vehicleLayoutManager: VehicleLayoutManager,
    private val mediaRemoteManager: MediaRemoteManager
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return DashboardScreen(carContext, repository, lightingManager, vehicleLayoutManager, mediaRemoteManager)
    }
}
