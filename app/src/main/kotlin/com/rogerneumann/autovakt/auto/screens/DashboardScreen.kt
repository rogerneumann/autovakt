package com.rogerneumann.autovakt.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.autovakt.R
import com.rogerneumann.autovakt.auto.render.GaugeSlotResolver
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.TripRepository
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.db.TripEntity
import com.rogerneumann.autovakt.media.MediaRemoteManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardScreen(
    carContext: CarContext,
    private val repository: OBD2Repository,
    private val mediaRemoteManager: MediaRemoteManager,
    private val tripRepository: TripRepository,
    private val vehicleLayoutManager: VehicleLayoutManager
) : Screen(carContext) {

    private var lastData: AutoVaktLiveData = AutoVaktLiveData()
    private var lastTrips: List<TripEntity> = emptyList()
    private var activeTabId = "gauges"
    private var lastLayoutKey: String = "gauge_layout_global"

    private val tabCallback = object : TabTemplate.TabCallback {
        override fun onTabSelected(contentId: String) {
            activeTabId = contentId
            invalidate()
        }
    }

    init {
        lifecycleScope.launch {
            combine(
                repository.liveData,
                mediaRemoteManager.currentMetadata
            ) { data, _ -> data }
                .collectLatest { data ->
                    lastData = data
                    invalidate()
                }
        }
        lifecycleScope.launch {
            tripRepository.getAllTrips().collectLatest { trips ->
                lastTrips = trips.take(5)
                invalidate()
            }
        }
        lifecycleScope.launch {
            repository.currentLayoutKey.collectLatest { key ->
                lastLayoutKey = key
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val data = lastData
        val metadata = mediaRemoteManager.currentMetadata.value
        val apiLevel = carContext.carAppApiLevel

        if (apiLevel < 6) {
            return buildFallbackTemplate(data)
        }

        val gaugesIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_grid_view_24)
        ).build()
        val mediaIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_music_note_24)
        ).build()
        val tripIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_history_24)
        ).build()

        val activeTemplate: Template = when (activeTabId) {
            "media" -> buildMediaTemplate(metadata.first, metadata.second)
            "trip"  -> buildTripTemplate()
            else    -> buildGaugesTemplate(data)
        }

        return TabTemplate.Builder(tabCallback)
            .setHeaderAction(Action.APP_ICON)
            .addTab(Tab.Builder()
                .setTitle("Gauges")
                .setIcon(gaugesIcon)
                .setContentId("gauges")
                .build())
            .addTab(Tab.Builder()
                .setTitle("Media")
                .setIcon(mediaIcon)
                .setContentId("media")
                .build())
            .addTab(Tab.Builder()
                .setTitle("Trip")
                .setIcon(tripIcon)
                .setContentId("trip")
                .build())
            .setTabContents(TabContents.Builder(activeTemplate).build())
            .setActiveTabContentId(activeTabId)
            .build()
    }

    private fun buildGaugesTemplate(data: AutoVaktLiveData): ListTemplate {
        val assignments = vehicleLayoutManager.getSlotAssignments(lastLayoutKey)
        val slots = GaugeSlotResolver.resolve(data, assignments, data.vehicleProfile, vehicleLayoutManager)
        val itemList = ItemList.Builder()
        slots.filter { it.value != "--" }.forEach { slot ->
            itemList.addItem(row(slot.label, "${slot.value} ${slot.unit}".trim()))
        }
        itemList.addItem(row("Host API", carContext.carAppApiLevel.toString()))
        return ListTemplate.Builder()
            .setSingleList(itemList.build())
            .build()
    }

    // Shown when AA host is below Car App API level 6 (no TabTemplate support).
    // PaneTemplate only allows 2 rows — use ListTemplate which supports 6+ items at all API levels.
    private fun buildFallbackTemplate(data: AutoVaktLiveData): ListTemplate {
        val assignments = vehicleLayoutManager.getSlotAssignments(lastLayoutKey)
        val slots = GaugeSlotResolver.resolve(data, assignments, data.vehicleProfile, vehicleLayoutManager)

        val (title, artist) = mediaRemoteManager.currentMetadata.value
        val mediaStr = when {
            title.isNotBlank() && artist.isNotBlank() -> "$title · $artist"
            title.isNotBlank() -> title
            else -> "No media"
        }

        val itemList = ItemList.Builder()
        slots.filter { it.value != "--" }.forEach { slot ->
            itemList.addItem(row(slot.label, "${slot.value} ${slot.unit}".trim()))
        }
        itemList.addItem(Row.Builder()
            .setTitle(mediaStr)
            .addText("Now Playing")
            .setOnClickListener { mediaRemoteManager.launchActiveMediaApp() }
            .build())
        itemList.addItem(Row.Builder()
            .setTitle("Trip History")
            .addText("View recorded trips")
            .setOnClickListener { screenManager.push(TripScreen(carContext, tripRepository)) }
            .build())

        return ListTemplate.Builder()
            .setSingleList(itemList.build())
            .setTitle("AutoVakt")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun buildMediaTemplate(title: String, artist: String): PaneTemplate {
        val displayTitle = title.ifBlank { "No media playing" }
        val rowBuilder = Row.Builder().setTitle(displayTitle)
        if (artist.isNotBlank()) rowBuilder.addText(artist)
        val pane = Pane.Builder()
            .addRow(rowBuilder.build())
            .addAction(Action.Builder()
                .setTitle("Open Music App")
                .setOnClickListener { mediaRemoteManager.launchActiveMediaApp() }
                .build())
            .build()
        return PaneTemplate.Builder(pane).build()
    }

    private fun buildTripTemplate(): ListTemplate {
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
                itemList.addItem(row(label, detail.ifBlank { "--" }))
            }
        }

        return ListTemplate.Builder().setSingleList(itemList.build()).build()
    }

    private fun row(label: String, value: String?): Row =
        Row.Builder()
            .setTitle(label)
            .addText(value ?: "--")
            .build()
}
