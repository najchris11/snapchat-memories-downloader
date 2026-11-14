package com.najdev.snapvault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic ZIP64 coverage through a real ContentResolver pipe: the first entry inflates to just over
 * 4 GiB, yet the archive is generated on the fly from one fixed buffer (see [Zip64Fixture]) and is never
 * stored. This proves the production streaming reader copes with a ZIP64 data descriptor and still finds
 * entries after it; it says nothing about any real cloud provider or a real multi-gigabyte export.
 */
@RunWith(AndroidJUnit4::class)
class Zip64PipeDocumentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val uri = Uri.parse("content://${instrumentation.context.packageName}.zip64/document/${Zip64PipeTestProvider.HUGE}")
    private val entryNames = listOf(Zip64Fixture.HUGE_ENTRY, Zip64Fixture.AFTER_ENTRY, Zip64Fixture.HISTORY_ENTRY)

    /** Everything the raw pipe scan needs: the stream's ends plus the data descriptor found in the middle. */
    private class RawScan(
        val total: Long,
        val head: ByteArray,
        val tail: ByteArray,
        val descriptor: ByteArray?,
        val descriptorEnd: Long,
    )

    /** Reads the pipe once, keeping only the first/last bytes and a 28-byte sliding window. */
    private fun scanRawPipe(): RawScan {
        val head = ByteArray(HEAD_BYTES)
        val tail = ByteArray(TAIL_BYTES)
        val window = ByteArray(DESCRIPTOR_BYTES + SIGNATURE_BYTES)
        var descriptor: ByteArray? = null
        var descriptorEnd = -1L
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "provider returned no stream" }
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (total < head.size) System.arraycopy(buffer, 0, head, total.toInt(), minOf(count, head.size - total.toInt()))
                for (i in 0 until count) {
                    window[(total % window.size).toInt()] = buffer[i]
                    total++
                    // A data descriptor is directly followed by the next local header.
                    if (descriptor == null && total >= window.size && windowMatches(window, total)) {
                        descriptor = ByteArray(DESCRIPTOR_BYTES) { k -> window[((total - window.size + k) % window.size).toInt()] }
                        descriptorEnd = total - SIGNATURE_BYTES
                    }
                }
                if (count >= tail.size) System.arraycopy(buffer, count - tail.size, tail, 0, tail.size)
                else {
                    System.arraycopy(tail, count, tail, 0, tail.size - count)
                    System.arraycopy(buffer, 0, tail, tail.size - count, count)
                }
            }
        }
        return RawScan(total, head, tail, descriptor, descriptorEnd)
    }

    private fun windowMatches(window: ByteArray, total: Long): Boolean {
        fun at(k: Int) = window[((total - window.size + k) % window.size).toInt()]
        val descriptorSignature = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
        val localSignature = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        return (0 until SIGNATURE_BYTES).all { at(it) == descriptorSignature[it] } &&
            (0 until SIGNATURE_BYTES).all { at(DESCRIPTOR_BYTES + it) == localSignature[it] }
    }

    private fun le(bytes: ByteArray, offset: Int = 0): ByteBuffer =
        ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.LITTLE_ENDIAN)

    @Test(timeout = TIMEOUT_MILLIS)
    fun theServedArchiveIsGenuineZip64WithAnOverFourGibEntry() {
        val scan = scanRawPipe()
        val descriptor = requireNotNull(scan.descriptor) { "no ZIP64 data descriptor followed by a local header" }

        // Local header of the huge entry: data-descriptor flag, deflate, sizes deferred to the descriptor.
        val head = le(scan.head)
        assertEquals(0x04034b50, head.getInt(0))
        assertTrue("data-descriptor flag", head.getShort(6).toInt() and 0x0008 != 0)
        assertEquals(8, head.getShort(8).toInt())
        assertEquals(45, head.getShort(4).toInt())
        val dataStart = 30L + (head.getShort(26).toInt() and 0xFFFF) + (head.getShort(28).toInt() and 0xFFFF)

        // End of archive: classic EOCD, ZIP64 locator, ZIP64 end-of-central-directory record.
        val tailStart = scan.total - scan.tail.size
        val eocd = le(scan.tail, scan.tail.size - EOCD_BYTES)
        assertEquals(0x06054b50, eocd.getInt(scan.tail.size - EOCD_BYTES))
        val locatorAt = scan.tail.size - EOCD_BYTES - LOCATOR_BYTES
        assertEquals(0x07064b50, le(scan.tail).getInt(locatorAt))
        val zip64EndAt = locatorAt - ZIP64_END_BYTES
        val zip64End = le(scan.tail)
        assertEquals(0x06064b50, zip64End.getInt(zip64EndAt))
        assertEquals(tailStart + zip64EndAt, zip64End.getLong(locatorAt + 8))
        assertEquals(entryNames.size.toLong(), zip64End.getLong(zip64EndAt + 32))
        val directoryOffset = zip64End.getLong(zip64EndAt + 48)
        assertTrue("central directory must lie in the retained tail", directoryOffset >= tailStart)

        // Central directory entry 1: sizes are the ZIP64 escape values, the real ones sit in extra field 0x0001.
        val central = le(scan.tail)
        var at = (directoryOffset - tailStart).toInt()
        assertEquals(0x02014b50, central.getInt(at))
        assertTrue("data-descriptor flag", central.getShort(at + 8).toInt() and 0x0008 != 0)
        assertEquals(8, central.getShort(at + 10).toInt())
        assertEquals(45, central.getShort(at + 6).toInt())
        assertEquals(0xFFFFFFFFL, central.getInt(at + 20).toLong() and 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFL, central.getInt(at + 24).toLong() and 0xFFFFFFFFL)
        val nameLength = central.getShort(at + 28).toInt() and 0xFFFF
        val extraLength = central.getShort(at + 30).toInt() and 0xFFFF
        val commentLength = central.getShort(at + 32).toInt() and 0xFFFF
        val extra = at + 46 + nameLength
        assertEquals(1, central.getShort(extra).toInt())
        assertEquals(16, central.getShort(extra + 2).toInt())
        val declaredSize = central.getLong(extra + 4)
        val declaredCompressed = central.getLong(extra + 12)
        assertEquals(Zip64Fixture.HUGE_SIZE, declaredSize)
        assertTrue("size must really exceed 32 bits", declaredSize > 0xFFFFFFFFL)
        assertTrue("transfer must stay far below the uncompressed size", declaredCompressed < (1L shl 30))
        assertEquals(0L, central.getInt(at + 42).toLong() and 0xFFFFFFFFL)

        // The streamed descriptor repeats those 64-bit sizes and the CRC, and the layout adds up exactly.
        val dd = le(descriptor)
        assertEquals(0x08074b50, dd.getInt(0))
        assertEquals(central.getInt(at + 16), dd.getInt(4))
        assertEquals(declaredCompressed, dd.getLong(8))
        assertEquals(Zip64Fixture.HUGE_SIZE, dd.getLong(16))
        assertEquals(dataStart + declaredCompressed + DESCRIPTOR_BYTES, scan.descriptorEnd)
        at += 46 + nameLength + extraLength + commentLength
        assertEquals(0x02014b50, central.getInt(at))
        assertEquals("next entry starts right after the 24-byte descriptor", scan.descriptorEnd, central.getInt(at + 42).toLong() and 0xFFFFFFFFL)
    }

    /** Runs [block] with the production document opener installed and proves no archive copy appeared. */
    private fun <T> withProductionReader(block: () -> T): T {
        val access = AndroidDocumentAccess(context)
        assertEquals(ExportSource.ZipFile(uri.toString()), requireNotNull(access.prepare(uri, false)).source)
        assertNull("A pipe cannot supply a seekable central directory", access.directory(uri.toString()))
        fun files() = listOf(context.cacheDir, context.filesDir).flatMap { dir -> dir.walkTopDown().filter { it.isFile }.map { it.path }.toList() }.toSet()
        val before = files()
        val previousOpen = JvmDocumentSources.open
        val previousDirectory = JvmDocumentSources.directory
        try {
            JvmDocumentSources.open = { requireNotNull(context.contentResolver.openInputStream(Uri.parse(it))) }
            JvmDocumentSources.directory = access::directory
            return block().also { assertEquals("the archive must not be cached", before, files()) }
        } finally {
            JvmDocumentSources.open = previousOpen
            JvmDocumentSources.directory = previousDirectory
        }
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun listingEntriesPastTheOverFourGibEntryStillFindsThem() {
        // Regression guard: a reader that mis-sized the ZIP64 descriptor would lose or fail every entry after it.
        assertEquals(entryNames, withProductionReader { listZipEntries(uri.toString()) })
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun historyAfterTheOverFourGibEntryIsReadAndAMissingEntryIsNull() {
        withProductionReader {
            assertEquals(Zip64Fixture.HISTORY_JSON, readZipEntryText(uri.toString(), Zip64Fixture.HISTORY_ENTRY))
            assertNull(readZipEntryText(uri.toString(), "missing.json"))
        }
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun everyEntryStreamsWithTheExactByteCountAndContent() {
        val pattern = Zip64Fixture.pattern()
        val expected = CRC32()
        var remaining = Zip64Fixture.HUGE_SIZE
        while (remaining > 0) {
            val length = minOf(pattern.size.toLong(), remaining).toInt()
            expected.update(pattern, 0, length)
            remaining -= length
        }
        val seen = mutableListOf<String>()
        withProductionReader {
            requireNotNull(documentZipReader(uri.toString())).forEachEntry { entry, stream ->
                seen += entry.name
                if (entry.name == Zip64Fixture.HUGE_ENTRY) {
                    val crc = CRC32()
                    var count = 0L
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        count += n
                    }
                    assertEquals(Zip64Fixture.HUGE_SIZE, count)
                    assertEquals(expected.value, crc.value)
                } else {
                    val bytes = ByteArrayOutputStream().also { stream.copyTo(it) }.toByteArray()
                    if (entry.name == Zip64Fixture.AFTER_ENTRY) assertArrayEquals(Zip64Fixture.afterBytes(), bytes)
                    else assertEquals(Zip64Fixture.HISTORY_JSON, bytes.toString(Charsets.UTF_8))
                }
            }
        }
        assertEquals(entryNames, seen)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 600_000L
        const val BUFFER_BYTES = 64 * 1024
        const val HEAD_BYTES = 256
        const val TAIL_BYTES = 4096
        const val SIGNATURE_BYTES = 4
        const val DESCRIPTOR_BYTES = 24
        const val EOCD_BYTES = 22
        const val LOCATOR_BYTES = 20
        const val ZIP64_END_BYTES = 56
    }
}
