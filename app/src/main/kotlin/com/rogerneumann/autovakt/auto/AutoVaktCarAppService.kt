package com.rogerneumann.autovakt.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import com.rogerneumann.autovakt.BuildConfig
import com.rogerneumann.autovakt.auto.screens.DashboardScreen
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.TripRepository
import com.rogerneumann.autovakt.media.MediaRemoteManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AutoVaktCarAppService : CarAppService() {

    @Inject lateinit var repository: OBD2Repository
    @Inject lateinit var mediaRemoteManager: MediaRemoteManager
    @Inject lateinit var tripRepository: TripRepository

    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return AutoVaktSession(repository, mediaRemoteManager, tripRepository)
    }
}

class AutoVaktSession(
    private val repository: OBD2Repository,
    private val mediaRemoteManager: MediaRemoteManager,
    private val tripRepository: TripRepository
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return DashboardScreen(carContext, repository, mediaRemoteManager, tripRepository)
    }
}
