package com.example.usbimageflasher

import com.example.usbimageflasher.usb.MassStorageDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Formats a MassStorageDevice as a single FAT32 volume with no partition
 * table (a "superfloppy" layout) -- the same layout most off-the-shelf USB
 * flash drives ship with.
 */
class Fat32Formatter(private val device: MassStorageDevice) {

    interface ProgressListener {
        fun onProgress(message: String)
        fun onComplete()
        fun onError(e: Exception)
    }

    fun format(volumeLabel: String = "USB DRIVE", listener: ProgressListener) {
        try {
            val bytesPerSector = device.blockSize
            val totalSectors = device.blockCount
            require(totalSectors > 0) { "Unknown drive size" }

            listener.onProgress("Calculating layout...")
            val layout = computeLayout(bytesPerSector, totalSectors)

            listener.onProgress("Writing boot sector...")
            writeBootSectors(layout, volumeLabel)

            listener.onProgress("Writing FAT tables...")
            writeFatTables(layout)

            listener.onProgress("Initializing root directory...")
            writeRootDirectory(layout)

            listener.onComplete()
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    private data class Layout(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val reservedSectorCount: Int,
        val numFats: Int,
        val fatSz32: Long,
        val totalSectors: Long,
        val rootCluster: Int,
        val fsInfoSector: Int,
        val backupBootSector: Int,
        val volumeId: Int
    )

    private fun computeLayout(bytesPerSector: Int, totalSectors: Long): Layout {
        val volumeBytes = totalSectors * bytesPerSector
        val sectorsPerCluster = when {
            volumeBytes < 260L * 1024 * 1024 ->
                throw IllegalStateException("Drive too small for FAT32 (need at least ~260MB)")
            volumeBytes < 8L * 1024 * 1024 * 1024 -> 8
            volumeBytes < 16L * 1024 * 1024 * 1024 -> 16
            volumeBytes < 32L * 1024 * 1024 * 1024 -> 32
            else -> 64
        }

        val reservedSectorCount = 32
        val numFats = 2

        val tmpVal1 = totalSectors - reservedSectorCount
        var tmpVal2 = (256L * sectorsPerCluster) + numFats
        tmpVal2 /= 2
        val fatSz32 = (tmpVal1 + tmpVal2 - 1) / tmpVal2

        val firstDataSector = reservedSectorCount + (numFats.toLong() * fatSz32)
        val dataSectors = totalSectors - firstDataSector
        val clusterCount = dataSectors / sectorsPerCluster
        require(clusterCount >= 65525) {
            "Computed cluster count too small for FAT32 ($clusterCount clusters)"
        }

        return Layout(
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectorCount = reservedSectorCount,
            numFats = numFats,
            fatSz32 = fatSz32,
            totalSectors = totalSectors,
            rootCluster = 2,
            fsInfoSector = 1,
            backupBootSector = 6,
            volumeId = Random.nextInt()
        )
    }

    private fun writeBootSectors(layout: Layout, volumeLabel: String) {
        val bs = layout.bytesPerSector
        val boot = ByteArray(bs)
        val bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)

        boot[0] = 0xEB.toByte(); boot[1] = 0x58; boot[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray().copyInto(boot, 3)

        bb.putShort(11, bs.toShort())
        boot[13] = layout.sectorsPerCluster.toByte()
        bb.putShort(14, layout.reservedSectorCount.toShort())
        boot[16] = layout.numFats.toByte()
        bb.putShort(17, 0)
        bb.putShort(19, 0)
        boot[21] = 0xF8.toByte()
        bb.putShort(22, 0)
        bb.putShort(24, 63)
        bb.putShort(26, 255)
        bb.putInt(28, 0)
        bb.putInt(32, layout.totalSectors.toInt())
        bb.putInt(36, layout.fatSz32.toInt())
        bb.putShort(40, 0)
        bb.putShort(42, 0)
        bb.putInt(44, layout.rootCluster)
        bb.putShort(48, layout.fsInfoSector.toShort())
        bb.putShort(50, layout.backupBootSector.toShort())
        boot[64] = 0x80.toByte()
        boot[66] = 0x29
        bb.putInt(67, layout.volumeId)
        padString(volumeLabel, 11).toByteArray().copyInto(boot, 71)
        "FAT32   ".toByteArray().copyInto(boot, 82)
        boot[510] = 0x55; boot[511] = 0xAA.toByte()

        device.writeRaw(0L, boot)

        val fsInfo = ByteArray(bs)
        val fb = ByteBuffer.wrap(fsInfo).order(ByteOrder.LITTLE_ENDIAN)
        fb.putInt(0, 0x41615252)
        fb.putInt(484, 0x61417272)
        fb.putInt(488, -1)
        fb.putInt(492, -1)
        fb.putInt(508, 0xAA550000L.toInt())
        device.writeRaw(layout.fsInfoSector.toLong(), fsInfo)

        device.writeRaw(layout.backupBootSector.toLong(), boot)
        device.writeRaw((layout.backupBootSector + 1).toLong(), fsInfo)
    }

    private fun writeFatTables(layout: Layout) {
        val bs = layout.bytesPerSector
        val firstFatSector = ByteArray(bs)
        val fb = ByteBuffer.wrap(firstFatSector).order(ByteOrder.LITTLE_ENDIAN)
        fb.putInt(0, 0x0FFFFFF8)
        fb.putInt(4, 0x0FFFFFFF)
        fb.putInt(8, 0x0FFFFFFF)

        val zeroChunk = ByteArray(1 shl 20)

        repeat(layout.numFats) { fatIndex ->
            val fatStart = layout.reservedSectorCount + fatIndex * layout.fatSz32
            device.writeRaw(fatStart, firstFatSector)

            var remainingSectors = layout.fatSz32 - 1
            var sector = fatStart + 1
            while (remainingSectors > 0) {
                val sectorsThisWrite = minOf(remainingSectors, (zeroChunk.size / bs).toLong())
                val chunk = if (sectorsThisWrite * bs == zeroChunk.size.toLong()) {
                    zeroChunk
                } else {
                    zeroChunk.copyOf((sectorsThisWrite * bs).toInt())
                }
                device.writeRaw(sector, chunk)
                sector += sectorsThisWrite
                remainingSectors -= sectorsThisWrite
            }
        }
    }

    private fun writeRootDirectory(layout: Layout) {
        val bs = layout.bytesPerSector
        val firstDataSector = layout.reservedSectorCount + layout.numFats * layout.fatSz32
        val clusterBytes = layout.sectorsPerCluster * bs
        val zeroCluster = ByteArray(clusterBytes)
        device.writeRaw(firstDataSector, zeroCluster)
    }

    private fun padString(s: String, len: Int): String {
        return (s + " ".repeat(len)).substring(0, len)
    }
}
