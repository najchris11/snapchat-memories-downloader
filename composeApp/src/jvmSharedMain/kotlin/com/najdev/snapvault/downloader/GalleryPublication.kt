package com.najdev.snapvault.downloader

import java.io.File
import java.io.IOException
import java.io.OutputStream

/** External MediaStore boundary: IDs originate only from this operation's create call. */
interface PendingMediaStore {
    fun create(name: String, mimeType: String): String?
    fun output(id: String): OutputStream?
    fun commit(id: String): Boolean
    fun discard(id: String)
}

object GalleryPublication {
    fun publish(source: File, mimeType: String, store: PendingMediaStore): Boolean {
        if (!source.isFile || source.length() == 0L) return false
        var created: String? = null
        return try {
            val id = store.create(source.name, mimeType) ?: return false
            created = id
            val output = store.output(id) ?: throw IOException("Could not open gallery destination")
            output.use { destination ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Gallery export cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                    }
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Gallery export cancelled")
            if (!store.commit(id)) throw IOException("Could not publish gallery item")
            true
        } catch (e: InterruptedException) {
            created?.let { runCatching { store.discard(it) } }
            throw e
        } catch (_: Exception) {
            created?.let { runCatching { store.discard(it) } }
            false
        }
    }
}
