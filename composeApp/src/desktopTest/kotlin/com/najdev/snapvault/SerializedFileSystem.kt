package com.najdev.snapvault

import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Path
import okio.Sink
import okio.Source

/**
 * A test filesystem that serialises every operation, including reads and writes on the
 * streams it hands out.
 *
 * okio's FakeFileSystem does no synchronisation at all. Tests where the view model writes on
 * the IO dispatcher while the test thread reads the same fake were racing it: under a busy
 * suite, a write would throw from inside the fake, the favorite writer would treat that as a
 * failed save and revert the heart, and the test would fail on a bug the app does not have.
 * The production filesystem is thread-safe; this makes the fake behave as if it were too.
 */
internal class SerializedFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    private val lock = Any()

    override fun canonicalize(path: Path): Path = synchronized(lock) { super.canonicalize(path) }
    override fun metadataOrNull(path: Path): FileMetadata? = synchronized(lock) { super.metadataOrNull(path) }
    override fun list(dir: Path): List<Path> = synchronized(lock) { super.list(dir) }
    override fun listOrNull(dir: Path): List<Path>? = synchronized(lock) { super.listOrNull(dir) }
    override fun openReadOnly(file: Path): FileHandle = synchronized(lock) { super.openReadOnly(file) }
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        synchronized(lock) { super.openReadWrite(file, mustCreate, mustExist) }
    override fun createDirectory(dir: Path, mustCreate: Boolean) = synchronized(lock) { super.createDirectory(dir, mustCreate) }
    override fun atomicMove(source: Path, target: Path) = synchronized(lock) { super.atomicMove(source, target) }
    override fun delete(path: Path, mustExist: Boolean) = synchronized(lock) { super.delete(path, mustExist) }
    override fun createSymlink(source: Path, target: Path) = synchronized(lock) { super.createSymlink(source, target) }

    override fun source(file: Path): Source {
        val real = synchronized(lock) { super.source(file) }
        return object : ForwardingSource(real) {
            override fun read(sink: Buffer, byteCount: Long): Long = synchronized(lock) { super.read(sink, byteCount) }
            override fun close() = synchronized(lock) { super.close() }
        }
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink = wrap(synchronized(lock) { super.sink(file, mustCreate) })

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
        wrap(synchronized(lock) { super.appendingSink(file, mustExist) })

    private fun wrap(real: Sink): Sink = object : ForwardingSink(real) {
        override fun write(source: Buffer, byteCount: Long) = synchronized(lock) { super.write(source, byteCount) }
        override fun flush() = synchronized(lock) { super.flush() }
        override fun close() = synchronized(lock) { super.close() }
    }
}
