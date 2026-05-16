package com.rogerneumann.autovakt.obd2

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class DeviceCapability {
    object GenericElm327 : DeviceCapability()
    object ObdLinkMxPlus : DeviceCapability()
    object ObdLinkCx : DeviceCapability()
}
