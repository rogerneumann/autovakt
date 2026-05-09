package com.rogerneumann.vakt.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rogerneumann.vakt.data.TripRepository
import com.rogerneumann.vakt.db.TripEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Phase 6A: ViewModel for the Trip History screen.
 * Maps [TripEntity] rows from Room into [TripUiModel] for display.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    repository: TripRepository
) : ViewModel() {

    val trips: StateFlow<List<TripUiModel>> = repository.getAllTrips()
        .map { list -> list.map { it.toUiModel() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}

/**
 * Presentation model — keeps all formatting logic out of the View.
 */
data class TripUiModel(
    val id: Long,
    val dateLabel: String,       // e.g. "May 9, 2026 — 09:31 AM"
    val durationLabel: String,   // e.g. "1h 23m" or "Active"
    val distanceLabel: String,   // e.g. "34.2 mi"
    val energyLabel: String,     // e.g. "9.1 kWh"
    val efficiencyLabel: String, // e.g. "3.76 mi/kWh"
    val socDeltaLabel: String,   // e.g. "85% → 62%"
    val isActive: Boolean
)

private fun TripEntity.toUiModel(): TripUiModel {
    val startDate = java.text.SimpleDateFormat(
        "MMM d, yyyy — hh:mm a", java.util.Locale.getDefault()
    ).format(java.util.Date(startTime))

    val durationMs = (endTime ?: System.currentTimeMillis()) - startTime
    val durationLabel = if (endTime == null) {
        "Active"
    } else {
        val h = durationMs / 3_600_000
        val m = (durationMs % 3_600_000) / 60_000
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    val efficiency = if (energyKwhUsed > 0f && distanceMiles > 0f) {
        "%.2f mi/kWh".format(distanceMiles / energyKwhUsed)
    } else "—"

    val socDelta = if (endSoc != null) {
        "${startSoc.toInt()}% → ${endSoc.toInt()}%"
    } else {
        "${startSoc.toInt()}% → …"
    }

    return TripUiModel(
        id = id,
        dateLabel = startDate,
        durationLabel = durationLabel,
        distanceLabel = "%.1f mi".format(distanceMiles),
        energyLabel = "%.1f kWh".format(energyKwhUsed),
        efficiencyLabel = efficiency,
        socDeltaLabel = socDelta,
        isActive = endTime == null
    )
}
