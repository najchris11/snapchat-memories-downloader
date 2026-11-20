package com.najdev.snapvault.downloader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GalleryPublicationTest {
    @Test
    fun galleryCopiesCommitOnlyAfterAllSourceBytesAreWritten() {
        val file = File.createTempFile("gallery-source", ".jpg").apply { writeText("media with metadata") }
        val output = ByteArrayOutputStream()
        val events = mutableListOf<String>()
        val store = object : PendingMediaStore {
            override fun create(name: String, mimeType: String): String { events += "create"; return "new-item" }
            override fun output(id: String): OutputStream = output
            override fun commit(id: String): Boolean { events += "commit"; assertEquals(file.readText(), output.toString()); return true }
            override fun discard(id: String) { events += "discard:$id" }
        }
        try {
            assertTrue(GalleryPublication.publish(file, "image/jpeg", store))
            assertEquals(listOf("create", "commit"), events)
            assertEquals("media with metadata", file.readText())
        } finally { file.delete() }
    }

    @Test
    fun aFailedGalleryWriteRemovesOnlyItsNewPendingItemAndKeepsTheSource() {
        val file = File.createTempFile("gallery-source", ".jpg").apply { writeText("original") }
        val discarded = mutableListOf<String>()
        var committed = false
        val store = object : PendingMediaStore {
            override fun create(name: String, mimeType: String): String = "owned-new-item"
            override fun output(id: String): OutputStream = object : OutputStream() { override fun write(value: Int) { throw java.io.IOException("full") } }
            override fun commit(id: String): Boolean { committed = true; return true }
            override fun discard(id: String) { discarded += id }
        }
        try {
            assertFalse(GalleryPublication.publish(file, "image/jpeg", store))
            assertEquals(listOf("owned-new-item"), discarded)
            assertFalse(committed)
            assertEquals("original", file.readText())
        } finally { file.delete() }
    }
    @Test
    fun cancellationAfterCopyDoesNotCommitThePendingGalleryItem() {
        val file = File.createTempFile("gallery-cancel", ".jpg").apply { writeText("original") }
        var committed = false
        val discarded = mutableListOf<String>()
        val store = object : PendingMediaStore {
            override fun create(name: String, mimeType: String): String = "new-cancelled-item"
            override fun output(id: String): OutputStream = object : ByteArrayOutputStream() {
                override fun close() { super.close(); Thread.currentThread().interrupt() }
            }
            override fun commit(id: String): Boolean { committed = true; return true }
            override fun discard(id: String) { discarded += id }
        }
        try {
            kotlin.test.assertFailsWith<InterruptedException> { GalleryPublication.publish(file, "image/jpeg", store) }
            assertFalse(committed)
            assertEquals(listOf("new-cancelled-item"), discarded)
        } finally { Thread.interrupted(); file.delete() }
    }
}
