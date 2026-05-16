package com.rogerneumann.autovakt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for MainActivity.
 * Bridges OBD2Repository to the phone UI and owns the demo mode toggle state.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: OBD2Repository,
    private val tripRepository: TripRepository
) : ViewModel() {

    val liveData = repository.liveData

    private var _isDemoMode = false
    val isDemoMode: Boolean get() = _isDemoMode

    /**
     * Starts synthetic demo data. Called from the hidden 5-tap easter egg.
     */
    fun startDemo() {
        _isDemoMode = true
        repository.start(useDemoMode = true)
    }

    /**
     * Stops demo mode. A subsequent call to startLive() or starting the
     * foreground service will resume real polling.
     */
    fun stopDemo() {
        _isDemoMode = false
        repository.stop()
    }

    /**
     * Stops the currently active trip. Persists the trip to the database
     * with the current SOC as the ending battery state.
     */
    fun stopTrip() {
        viewModelScope.launch {
            tripRepository.endCurrentTrip(
                finalSoc = liveData.value.soc ?: 0f
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT stop the repository here — the foreground service owns its lifecycle.
    }
}
