package com.fastshare

/** Shared state read by the UI and written by the service. */
object Transfer {
    const val PORT = 8988
    const val DISCOVERY_PORT = 8989
    const val MAGIC = 0x46534831 // "FSH1"

    @Volatile var status = "Idle"
    @Volatile var fileName = ""
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var fileIndex = 0
    @Volatile var fileCount = 0
    @Volatile var receiving = false
    @Volatile var sending = false
    @Volatile var cancel = false
}
