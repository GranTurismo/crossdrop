package com.crossdrop.core

import java.util.UUID

/** Constants shared by every transport layer (and the future Rust core). */
object CrossDrop {
    /** mDNS/NSD service type for LAN discovery. */
    const val NSD_SERVICE_TYPE = "_crossdrop._tcp."

    /** BLE 5.0 advertisement service UUID — full 128-bit, app-owned. */
    val BLE_SERVICE_UUID: UUID = UUID.fromString("cd0d5e55-7a11-4c0f-9e2b-3f1a2b3c4d5e")

    /** Bytes of the rotating instance token embedded in BLE service data / NSD TXT. */
    const val INSTANCE_ID_BYTES = 8
}
