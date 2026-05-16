package com.rogerneumann.autovakt.util

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Application-level singleton holding the current phone display mode.
 * Written by MainActivity on swipe; read by AutoVaktMediaBrowserService to
 * determine which bitmap layout to render in the Coolwalk slot.
 *
 * Values: "HYBRID" | "TELEMETRY" | "MEDIA"
 */
object AutoVaktDisplayState {
    val displayMode = MutableStateFlow("HYBRID")
}
