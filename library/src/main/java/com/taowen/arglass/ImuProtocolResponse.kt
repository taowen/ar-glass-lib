package com.taowen.arglass

/**
 * An observed protocol-query response, not factory calibration or a decoded protocol ID.
 * [format] identifies the transport framing; [command] is the actual query command.
 * Empty bytes mean the query returned no response. A null response object means no query
 * has completed. Copies prevent callers from modifying the session's retained evidence.
 */
class ImuProtocolResponse(val format: String, val command: Int, bytes: ByteArray) {
    private val response = bytes.copyOf()
    val bytes: ByteArray get() = response.copyOf()
}
