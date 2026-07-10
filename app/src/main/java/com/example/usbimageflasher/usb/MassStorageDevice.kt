package com.example.usbimageflasher.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MassStorageDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) {
    var blockSize: Int = 512
        private set
    var blockCount: Long = 0
        private set

    private var tag = 1

    companion object {
        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355
        private const val CBW_LEN = 31
        private const val CSW_LEN = 13
        private const val DIR_IN: Byte = -0x80
        private const val DIR_OUT: Byte = 0x00
        private const val TRANSFER_TIMEOUT_MS = 10000
        private const val MAX_SINGLE_TRANSFER = 16 * 1024

        fun open(connection: UsbDeviceConnection, device: UsbDevice): MassStorageDevice? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val isMassStorage = intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                val isScsi = intf.interfaceSubclass == 0x06
                val isBulkOnly = intf.interfaceProtocol == 0x50
                if (isMassStorage && isScsi && isBulkOnly) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                    if (inEp != null && outEp != null &&
                        connection.claimInterface(intf, true)
                    ) {
                        val msd = MassStorageDevice(connection, intf, inEp, outEp)
                        msd.reset()
                        Thread.sleep(100)
                        msd.waitUntilReady()
                        var opened = false
                        repeat(5) { attempt ->
                            if (opened) return@repeat
                            try {
                                msd.readCapacity()
                                opened = true
                            } catch (e: IOException) {
                                if (attempt == 4) throw e
                                Thread.sleep(200)
                            }
                        }
                        return msd
                    }
                }
            }
            return null
        }
    }

    private fun reset() {
        connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 1000)
        connection.clearFeature(bulkIn)
        connection.clearFeature(bulkOut)
    }

    private fun UsbDeviceConnection.clearFeature(ep: UsbEndpoint) {
        controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000)
    }

    private fun nextTag(): Int = tag++

    private fun executeCommand(
        cb: ByteArray,
        dataLen: Int,
        directionIn: Boolean,
        dataOut: ByteArray? = null
    ): ByteArray? {
        val thisTag = nextTag()
        val cbw = ByteBuffer.allocate(CBW_LEN).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(CBW_SIGNATURE)
        cbw.putInt(thisTag)
        cbw.putInt(dataLen)
        cbw.put(if (dataLen == 0) 0 else if (directionIn) DIR_IN else DIR_OUT)
        cbw.put(0)
        cbw.put(cb.size.toByte())
        val cbPadded = ByteArray(16)
        System.arraycopy(cb, 0, cbPadded, 0, cb.size)
        cbw.put(cbPadded)

        val cbwBytes = cbw.array()
        val sent = connection.bulkTransfer(bulkOut, cbwBytes, cbwBytes.size, TRANSFER_TIMEOUT_MS)
        if (sent != cbwBytes.size) throw IOException("Failed to send CBW (sent=$sent)")

        var received: ByteArray? = null
        if (dataLen > 0) {
            received = if (directionIn) {
                readExact(dataLen)
            } else {
                writeExact(dataOut!!)
                null
            }
        }

        val csw = readCsw()
        if (csw.tag != thisTag) throw IOException("CSW tag mismatch")
        if (csw.status != 0) throw IOException("SCSI command failed, status=${csw.status}")
        return received
    }

    private fun readExact(len: Int): ByteArray {
        val out = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val chunk = minOf(MAX_SINGLE_TRANSFER, len - offset)
            val buf = ByteArray(chunk)
            val n = connection.bulkTransfer(bulkIn, buf, chunk, TRANSFER_TIMEOUT_MS)
            if (n <= 0) throw IOException("Bulk IN read failed at offset $offset")
            System.arraycopy(buf, 0, out, offset, n)
            offset += n
        }
        return out
    }

    private fun writeExact(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(MAX_SINGLE_TRANSFER, data.size - offset)
            val n = connection.bulkTransfer(bulkOut, data, offset, chunk, TRANSFER_TIMEOUT_MS)
            if (n != chunk) throw IOException("Bulk OUT write failed at offset $offset")
            offset += chunk
        }
    }

    private data class Csw(val tag: Int, val residue: Int, val status: Int)

    private fun readCsw(): Csw {
        val buf = ByteArray(CSW_LEN)
        val n = connection.bulkTransfer(bulkIn, buf, CSW_LEN, TRANSFER_TIMEOUT_MS)
        if (n != CSW_LEN) throw IOException("Failed to read CSW (n=$n)")
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val sig = bb.int
        if (sig != CSW_SIGNATURE) throw IOException("Bad CSW signature")
        val cswTag = bb.int
        val residue = bb.int
        val status = bb.get().toInt() and 0xFF
        return Csw(cswTag, residue, status)
    }

    private fun waitUntilReady(attempts: Int = 10) {
        val cb = ByteArray(6)
        repeat(attempts) {
            try {
                executeCommand(cb, 0, directionIn = true)
                return
            } catch (e: IOException) {
                Thread.sleep(200)
            }
        }
    }

    private fun readCapacity() {
        val cb = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val data = executeCommand(cb, 8, directionIn = true) ?: throw IOException("No capacity data")
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val blkSize = bb.int
        blockCount = lastLba + 1
        blockSize = blkSize
    }

    fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "data must be a multiple of blockSize" }
        val numBlocks = data.size / blockSize
        require(numBlocks in 1..65535) { "chunk too large for a single WRITE(10)" }

        val cb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        cb.put(0x2A)
        cb.put(0)
        cb.putInt(startBlock.toInt())
        cb.put(0)
        cb.putShort(numBlocks.toShort())
        executeCommand(cb.array(), data.size, directionIn = false, dataOut = data)
    }

    /** Writes [data] of any size starting at [startBlock], auto-chunking to stay under the 65535-block limit. */
    fun writeRaw(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "data must be a multiple of blockSize" }
        var offset = 0
        var block = startBlock
        val maxBytesPerCall = 65535 * blockSize
        while (offset < data.size) {
            val chunkBytes = minOf(maxBytesPerCall, data.size - offset)
            val chunk = if (chunkBytes == data.size && offset == 0) {
                data
            } else {
                data.copyOfRange(offset, offset + chunkBytes)
            }
            writeBlocks(block, chunk)
            block += (chunkBytes / blockSize).toLong()
            offset += chunkBytes
        }
    }

    /** Reads [numBlocks] blocks starting at [startBlock] using SCSI READ(10). */
    fun readBlocks(startBlock: Long, numBlocks: Int): ByteArray {
        require(numBlocks in 1..65535) { "chunk too large for a single READ(10)" }
        val cb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        cb.put(0x28)
        cb.put(0)
        cb.putInt(startBlock.toInt())
        cb.put(0)
        cb.putShort(numBlocks.toShort())
        val dataLen = numBlocks * blockSize
        return executeCommand(cb.array(), dataLen, directionIn = true)
            ?: throw IOException("No data returned from READ(10)")
    }

    /** Reads [numBytes] bytes starting at [startBlock], auto-chunking to stay under the 65535-block limit. */
    fun readRaw(startBlock: Long, numBytes: Long): ByteArray {
        require(numBytes % blockSize == 0L) { "numBytes must be a multiple of blockSize" }
        val out = ByteArray(numBytes.toInt())
        var offset = 0
        var block = startBlock
        val maxBytesPerCall = 65535 * blockSize
        while (offset < out.size) {
            val chunkBytes = minOf(maxBytesPerCall, out.size - offset)
            val numBlocksThis = chunkBytes / blockSize
            val chunk = readBlocks(block, numBlocksThis)
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            block += numBlocksThis.toLong()
            offset += chunkBytes
        }
        return out
    }

    /** Issues SCSI START STOP UNIT with start=0 to flag the drive as safe to remove. */
    fun eject() {
        val cb = ByteArray(6)
        cb[0] = 0x1B // START STOP UNIT
        cb[4] = 0x00 // start=0 (stop), immediate=0
        try {
            executeCommand(cb, 0, directionIn = true)
        } catch (_: IOException) {
            // Some drives don't ACK STOP UNIT cleanly; ignore and proceed to release the interface.
        }
    }

    fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
    }
}
