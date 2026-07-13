package com.taowen.arglass.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

internal fun UsbDevice.interfaceById(id: Int): UsbInterface = (0 until interfaceCount)
    .map(::getInterface).first { it.id == id }

internal fun UsbInterface.endpoint(direction: Int): UsbEndpoint = (0 until endpointCount)
    .map(::getEndpoint).first { it.direction == direction }

internal fun UsbInterface.inputEndpoint(): UsbEndpoint = endpoint(UsbConstants.USB_DIR_IN)
internal fun UsbInterface.outputEndpoint(): UsbEndpoint = endpoint(UsbConstants.USB_DIR_OUT)
