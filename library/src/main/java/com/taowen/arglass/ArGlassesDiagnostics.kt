package com.taowen.arglass

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

object ArGlassesDiagnostics {
    private const val USB_FILE = "usb-transfers.bin"
    private const val CRASH_FILE = "crashes.bin"
    private const val USB_MAGIC = 0x41525553
    private const val CRASH_MAGIC = 0x41524352
    private val lock = Any()
    @Volatile private var directory: File? = null

    internal fun initialize(context: Context) {
        if (directory != null) return
        synchronized(lock) {
            if (directory == null) directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
            NativeBridge.configureUsbDiagnostics(File(requireNotNull(directory), USB_FILE).absolutePath)
        }
    }

    internal fun recordUsb(
        device: UsbDevice,
        operation: Int,
        addressOrRequestType: Int,
        request: Int,
        value: Int,
        index: Int,
        result: Int,
        payload: ByteArray,
    ) = append(USB_FILE) { output ->
        output.writeInt(USB_MAGIC)
        output.writeShort(1)
        output.writeLong(System.currentTimeMillis())
        output.writeInt(device.vendorId)
        output.writeInt(device.productId)
        output.writeByte(operation)
        output.writeInt(addressOrRequestType)
        output.writeInt(request)
        output.writeInt(value)
        output.writeInt(index)
        output.writeInt(result)
        output.writeInt(payload.size)
        output.write(payload)
    }

    internal fun recordPermission(device: UsbDevice, requested: Boolean, granted: Boolean) =
        recordUsb(device, if (requested) 3 else 4, 0, 0, 0, 0, if (granted) 1 else 0, byteArrayOf())

    fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        initialize(context)
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().toByteArray(Charsets.UTF_8)
        append(CRASH_FILE) { output ->
            output.writeInt(CRASH_MAGIC)
            output.writeShort(1)
            output.writeLong(System.currentTimeMillis())
            writeBytes(output, thread.name.toByteArray(Charsets.UTF_8))
            writeBytes(output, trace)
        }
    }

    fun exportToTree(context: Context, treeUri: Uri): List<String> {
        initialize(context)
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return listOf(USB_FILE, CRASH_FILE).map { name ->
            val source = File(requireNotNull(directory), name).apply { if (!exists()) createNewFile() }
            val target = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name))
            resolver.openOutputStream(target, "w").use { output ->
                requireNotNull(output)
                source.inputStream().use { it.copyTo(output) }
            }
            name
        }
    }

    private fun append(name: String, write: (DataOutputStream) -> Unit) {
        val dir = directory ?: return
        synchronized(lock) {
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(dir, name), true))).use(write)
        }
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
