package com.example.usbimageflasher

import android.content.ContentResolver
import android.net.Uri
import com.example.usbimageflasher.usb.MassStorageDevice
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Streams an OS image (.img, .img.gz, or .img.xz) onto a [MassStorageDevice],
 * writing raw sectors directly -- equivalent to `dd if=image.img of=/dev/sdX`
 * but issued over USB bulk transfers instead of a block device.
 */
class ImageFlasher(
    private val resolver: ContentResolver,
    private val device: MassStorageDevice
) {
    interface ProgressListener {
        fun onScanProgress(bytesScanned: Long) {}
        /** [totalBytes] is -1 if unknown. */
        fun onProgress(bytesWritten: Long, totalBytes: Long)
        fun onVerifyProgress(bytesVerified: Long, totalBytes: Long) {}
        fun onVerifyResult(success: Boolean) {}
        fun onComplete()
        fun onError(e: Exception)
    }

    // Number of device sectors written/read per SCSI command.
    private val sectorsPerChunk = 128

    fun flash(imageUri: Uri, imageSizeBytes: Long, verifyAfter: Boolean, listener: ProgressListener) {
        try {
            val name = resolveDisplayName(imageUri).lowercase()
            val isCompressed = name.endsWith(".xz") || name.endsWith(".gz")

            // For compressed streams the file size on disk isn't the number of bytes
            // that will actually be written -- pre-scan once (decompress + discard) to
            // get an accurate total for the progress bar during the real write pass.
            val effectiveTotalBytes = if (isCompressed) {
                scanDecompressedSize(imageUri, name, listener)
            } else {
                imageSizeBytes
            }

            val raw = resolver.openInputStream(imageUri)
                ?: throw IllegalStateException("Cannot open selected image")
            val decompressed = wrapDecompressor(raw, name)

            val blockSize = device.blockSize
            val chunkBytes = blockSize * sectorsPerChunk
            val buffer = ByteArray(chunkBytes)

            var lba = 0L
            var totalWritten = 0L
            var totalDeviceBytesWritten = 0L
            val maxBytes = device.blockCount * blockSize
            val digest = MessageDigest.getInstance("MD5")

            decompressed.use { input ->
                while (true) {
                    val n = readFully(input, buffer)
                    if (n <= 0) break

                    if (totalWritten + n > maxBytes) {
                        throw IllegalStateException(
                            "Image is larger than the target drive " +
                                "(${maxBytes / (1024 * 1024)} MB available)"
                        )
                    }

                    val toWrite = if (n == chunkBytes) {
                        buffer
                    } else {
                        val padded = n.roundUpToSector(blockSize)
                        buffer.copyOf(padded)
                    }

                    device.writeBlocks(lba, toWrite)
                    digest.update(toWrite)
                    lba += toWrite.size / blockSize
                    totalWritten += n
                    totalDeviceBytesWritten += toWrite.size
                    listener.onProgress(totalWritten, effectiveTotalBytes)
                }
            }

            val writtenHash = digest.digest()
            listener.onComplete()

            if (verifyAfter) {
                verify(totalDeviceBytesWritten, writtenHash, listener)
            }
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    private fun verify(totalDeviceBytes: Long, expectedHash: ByteArray, listener: ProgressListener) {
        try {
            val blockSize = device.blockSize
            val chunkBytes = blockSize * sectorsPerChunk
            val digest = MessageDigest.getInstance("MD5")

            var lba = 0L
            var totalVerified = 0L
            while (totalVerified < totalDeviceBytes) {
                val remaining = totalDeviceBytes - totalVerified
                val thisChunkBytes = minOf(chunkBytes.toLong(), remaining).toInt()
                val numBlocks = thisChunkBytes / blockSize
                val chunk = device.readBlocks(lba, numBlocks)
                digest.update(chunk)
                lba += numBlocks
                totalVerified += chunk.size
                listener.onVerifyProgress(totalVerified, totalDeviceBytes)
            }

            val readHash = digest.digest()
            listener.onVerifyResult(readHash.contentEquals(expectedHash))
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    /** Decompresses the stream once, discarding output, purely to learn the real byte count. */
    private fun scanDecompressedSize(imageUri: Uri, name: String, listener: ProgressListener): Long {
        val raw = resolver.openInputStream(imageUri)
            ?: throw IllegalStateException("Cannot open selected image")
        val decompressed = wrapDecompressor(raw, name)
        val buffer = ByteArray(1 shl 20)
        var total = 0L
        decompressed.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                listener.onScanProgress(total)
            }
        }
        return total
    }

    private fun wrapDecompressor(raw: InputStream, name: String): InputStream {
        val buffered = BufferedInputStream(raw, 1 shl 20)
        return when {
            name.endsWith(".xz") -> XZInputStream(buffered)
            name.endsWith(".gz") -> GZIPInputStream(buffered, 1 shl 20)
            else -> buffered
        }
    }

    private fun Int.roundUpToSector(sectorSize: Int): Int {
        val rem = this % sectorSize
        return if (rem == 0) this else this + (sectorSize - rem)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun resolveDisplayName(uri: Uri): String {
        return uri.lastPathSegment ?: ""
    }
}
