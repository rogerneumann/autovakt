package com.rogerneumann.vakt.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.rogerneumann.vakt.R

/**
 * The primary full-screen dashboard for Vakt.
 * Implements a multi-row layout for telemetry and media controls.
 */
class DashboardScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("State of Charge")
                    .addText("87%")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Real-time Power")
                    .addText("32.5 kW")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Efficiency")
                    .addText("3.8 mi/kWh avg")
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("New Trip")
                    .setOnClickListener { /* TODO: Manual trip reset */ }
                    .build()
            )

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeaderAction(Action.APP_ICON)
            .setTitle("Vakt Dashboard")
            .build()
    }
}
