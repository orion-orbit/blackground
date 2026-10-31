package com.minimal.hifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

data class CoverArtSliceRef(val sourceUri: Uri, val byteOffset: Long = 0L, val byteLength: Int = 0)

object CoverArtExtractor {
    private val bitmapCache = object : LruCache<String, ImageBitmap>(6) {}

    suspend fun loadArtworkForTrack(
        context: Context,
        trackUri: Uri,
        fallbackFolderImageUri: Uri? = null,
        targetSizePx: Int = 512
    ): Pair<ImageBitmap?, ByteArray?> = withContext(Dispatchers.IO) {
        val key = trackUri.toString()
        val cached = bitmapCache.get(key)
        val ref = locateEmbeddedArtSlice(context, trackUri) ?: return@withContext (cached to null)
        val rawBytes = readSliceBytes(context, ref) ?: return@withContext (cached to null)
        if (cached != null) return@withContext (cached to rawBytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        var sample = 1
        while ((bounds.outHeight / 2 / sample) >= targetSizePx && (bounds.outWidth / 2 / sample) >= targetSizePx) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return@withContext (null to null)

        val img = decoded.asImageBitmap()
        bitmapCache.put(key, img)
        val jpeg = ByteArrayOutputStream().use { bos -> decoded.compress(Bitmap.CompressFormat.JPEG, 82, bos); bos.toByteArray() }
        img to jpeg
    }

    private fun locateEmbeddedArtSlice(context: Context, uri: Uri): CoverArtSliceRef? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val magic = ByteBuffer.allocate(4)
                    if (ch.read(magic) < 4) return null
                    if (String(magic.array(), StandardCharsets.US_ASCII) == "fLaC") locateFlacPicture(ch, uri) else null
                }
            }
        } catch (e: Exception) { null }
    }

    private fun locateFlacPicture(ch: FileChannel, uri: Uri): CoverArtSliceRef? {
        var pos = 4L; var isLast = false
        val hdr = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        while (!isLast && pos + 4 <= ch.size()) {
            hdr.clear(); ch.position(pos); if (ch.read(hdr) < 4) break; hdr.flip()
            val b0 = hdr.get().toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val type = b0 and 0x7F
            val len = ((hdr.get().toInt() and 0xFF) shl 16) or ((hdr.get().toInt() and 0xFF) shl 8) or (hdr.get().toInt() and 0xFF)
            val start = pos + 4L
            if (type == 6 && len > 32) {
                val ib = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                ch.position(start + 4L); ib.clear(); ch.read(ib); ib.flip()
                val mimeLen = ib.int.coerceAtLeast(0)
                val descOff = start + 8L + mimeLen
                ib.clear(); ch.position(descOff); ch.read(ib); ib.flip()
                val descLen = ib.int.coerceAtLeast(0)
                val dataLenOff = descOff + 4L + descLen + 16L
                ib.clear(); ch.position(dataLenOff); ch.read(ib); ib.flip()
                val dataLen = ib.int
                val imgOff = dataLenOff + 4L
                if (dataLen in 64..20_971_520 && imgOff + dataLen <= ch.size()) return CoverArtSliceRef(uri, imgOff, dataLen)
            }
            pos = start + len
        }
        return null
    }

    private fun readSliceBytes(context: Context, ref: CoverArtSliceRef): ByteArray? {
        return try {
            context.contentResolver.openFileDescriptor(ref.sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val buf = ByteBuffer.allocate(ref.byteLength)
                    ch.position(ref.byteOffset)
                    var tot = 0
                    while (tot < ref.byteLength) { val r = ch.read(buf); if (r <= 0) break; tot += r }
                    buf.array()
                }
            }
        } catch (e: Exception) { null }
    }
}
