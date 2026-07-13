package com.taowen.arglass.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.taowen.arglass.ArGlassesDiagnostics

internal fun UsbDevice.interfaceById(id: Int): UsbInterface = (0 until interfaceCount)
    .map(::getInterface).first { it.id == id }

internal fun UsbInterface.endpoint(direction: Int): UsbEndpoint = (0 until endpointCount)
    .map(::getEndpoint).first { it.direction == direction }

internal fun UsbInterface.inputEndpoint(): UsbEndpoint = endpoint(UsbConstants.USB_DIR_IN)
internal fun UsbInterface.outputEndpoint(): UsbEndpoint = endpoint(UsbConstants.USB_DIR_OUT)

internal fun UsbDeviceConnection.tracedBulkTransfer(device: UsbDevice, endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
    val result = bulkTransfer(endpoint, buffer, length, timeout)
    val payloadLength = if (endpoint.direction == UsbConstants.USB_DIR_IN) result.coerceIn(0, buffer.size) else length.coerceIn(0, buffer.size)
    ArGlassesDiagnostics.recordUsb(device, if (endpoint.direction == UsbConstants.USB_DIR_IN) 1 else 2, endpoint.address, 0, 0, 0, result, buffer.copyOf(payloadLength))
    return result
}

internal fun UsbDeviceConnection.tracedControlTransfer(device: UsbDevice, requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeout: Int): Int {
    val result = controlTransfer(requestType, request, value, index, buffer, length, timeout)
    val input = requestType and UsbConstants.USB_DIR_IN != 0
    val payloadLength = if (input) result.coerceIn(0, buffer.size) else length.coerceIn(0, buffer.size)
    ArGlassesDiagnostics.recordUsb(device, if (input) 1 else 2, requestType, request, value, index, result, buffer.copyOf(payloadLength))
    return result
}
