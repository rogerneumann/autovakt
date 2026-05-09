package com.rogerneumann.vakt.ui

import androidx.lifecycle.ViewModel
import com.rogerneumann.vakt.data.OBD2Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for MainActivity.
 * Bridges OBD2Repository to the phone UI and owns the demo mode toggle state.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: OBD2Repository
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

    override fun onCleared() {
        super.onCleared()
        // Do NOT stop the repository here — the foreground service owns its lifecycle.
    }
}
