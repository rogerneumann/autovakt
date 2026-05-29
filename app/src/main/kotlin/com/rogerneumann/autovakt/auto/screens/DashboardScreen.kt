package com.rogerneumann.autovakt.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.obd2.ConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DashboardScreen(
    carContext: CarContext,
    private val repository: OBD2Repository
) : Screen(carContext) {

    private var lastData: AutoVaktLiveData = AutoVaktLiveData()

    init {
        lifecycleScope.launch {
            repository.liveData.collectLatest { data ->
                lastData = data
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val data = lastData

        val title = when (data.connectionState) {
            is ConnectionState.Connected   -> "AutoVakt — Connected"
            is ConnectionState.Connecting  -> "AutoVakt — Connecting…"
            else                           -> "AutoVakt — Not Connected"
        }

        return GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("New Trip")
                            .setOnClickListener {
                                lifecycleScope.launch { repository.startManualTrip() }
                            }
                            .build()
                    )
                    .build()
            )
            .setSingleList(buildItems(data))
            .build()
    }

    private fun buildItems(data: AutoVaktLiveData): ItemList {
        // Fixed 6-cell grid. Values prefer EV fields, fall back to ICE equivalents,
        // then "--" when the connected vehicle doesn't provide the metric.
        return ItemList.Builder()
            .addItem(cell("Battery",
                data.soc?.let { "%.0f%%".format(it) }))
            .addItem(cell("Power",
                data.powerKw?.let { "%.1f kW".format(it) }
                    ?: data.engineLoad?.let { "%.0f%% load".format(it) }))
            .addItem(cell("Speed",
                data.speedMph?.let { "%.0f mph".format(it) }))
            .addItem(cell("Efficiency",
                data.instantMiPerKwh?.let { "%.1f mi/kWh".format(it) }
                    ?: data.instantMpg?.let { "%.0f mpg".format(it) }))
            .addItem(cell("Avg Efficiency",
                data.averageMiPerKwh?.let { "%.1f mi/kWh".format(it) }
                    ?: data.averageMpg?.let { "%.0f mpg".format(it) }))
            .addItem(cell("Temp",
                data.battTempMaxC?.let { "%.0f°C batt".format(it) }
                    ?: data.coolantTempC?.let { "%.0f°C coolant".format(it) }))
            .build()
    }

    private fun cell(label: String, value: String?): GridItem =
        GridItem.Builder()
            .setTitle(label)
            .setText(value ?: "--")
            .build()
}
