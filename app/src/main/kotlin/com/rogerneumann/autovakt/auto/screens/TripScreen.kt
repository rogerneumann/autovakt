package com.rogerneumann.autovakt.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.autovakt.data.TripRepository
import com.rogerneumann.autovakt.db.TripEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripScreen(
    carContext: CarContext,
    private val tripRepository: TripRepository
) : Screen(carContext) {

    private var lastTrips: List<TripEntity> = emptyList()

    init {
        lifecycleScope.launch {
            tripRepository.getAllTrips().collectLatest { trips ->
                lastTrips = trips.take(8)
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val fmt = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
        val itemList = ItemList.Builder()

        if (lastTrips.isEmpty()) {
            itemList.setNoItemsMessage("No trips recorded yet")
        } else {
            lastTrips.forEach { trip ->
                val label = if (trip.endTime == null) "Active trip"
                            else fmt.format(Date(trip.startTime))
                val distance = if (trip.distanceMiles > 0f) "%.1f mi".format(trip.distanceMiles) else null
                val socDelta = if (trip.endSoc != null)
                    "%.0f%%→%.0f%%".format(trip.startSoc, trip.endSoc)
                else
                    "%.0f%% SOC".format(trip.startSoc)
                val duration = trip.endTime?.let { end ->
                    val mins = ((end - trip.startTime) / 60_000).toInt()
                    if (mins >= 60) "%dh %dm".format(mins / 60, mins % 60) else "${mins}m"
                }
                val detail = listOfNotNull(distance, socDelta, duration).joinToString(" · ")
                itemList.addItem(
                    Row.Builder()
                        .setTitle(label)
                        .addText(detail.ifBlank { "--" })
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Trip History")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemList.build())
            .build()
    }
}
