package com.najdev.snapvault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Streams a genuine ZIP64 archive whose first entry holds more than 4 GiB of uncompressed data.
 *
 * <p>Nothing is stored: a fixed 64 KiB pattern buffer is deflated on the fly (the pattern compresses
 * to a few MiB) and handed straight to the output, so neither the archive nor the payload ever exists
 * in memory or on disk. The huge entry is written the way a streaming writer must: local header with
 * the data-descriptor flag and no sizes, deflated data, then a ZIP64 data descriptor with 8-byte sizes;
 * the central directory carries the ZIP64 extended-information extra field and the archive ends with a
 * ZIP64 end-of-central-directory record and locator. Pure Java with no Android imports so a host JDK
 * can generate the identical bytes for an independent format check.
 */
public final class Zip64Fixture {
    public static final String HUGE_ENTRY = "memories/huge.bin";
    public static final String AFTER_ENTRY = "memories/after.jpg";
    public static final String HISTORY_ENTRY = "memories_history.json";
    public static final String HISTORY_JSON = "{\"Saved Media\":[]}";
    /** 4 GiB plus a few KiB: strictly over the 32-bit limit, and not a multiple of the buffer size. */
    public static final long HUGE_SIZE = (1L << 32) + 12_345L;
    public static final int AFTER_SIZE = 1500;
    private static final int PATTERN_SIZE = 64 * 1024;
    private static final int PERIOD = 13;
    private static final int DOS_DATE = ((2026 - 1980) << 9) | (11 << 5) | 9;
    private static final int DOS_TIME = 12 << 11;
    private static final int FLAG_DATA_DESCRIPTOR = 0x0008;
    private static final int FLAG_UTF8 = 0x0800;
    private static final long MAGIC32 = 0xFFFFFFFFL;

    private Zip64Fixture() { }

    /** The repeating buffer the huge entry is made of. Its last pass is cut short to reach {@link #HUGE_SIZE}. */
    public static byte[] pattern() {
        byte[] buffer = new byte[PATTERN_SIZE];
        for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) ('a' + (i % PERIOD));
        return buffer;
    }

    /** Small incompressible entry stored after the huge one. */
    public static byte[] afterBytes() {
        byte[] bytes = new byte[AFTER_SIZE];
        new Random(64).nextBytes(bytes);
        return bytes;
    }

    private static final class Counting extends OutputStream {
        private final OutputStream target;
        long count;
        Counting(OutputStream target) { this.target = target; }
        @Override public void write(int b) throws IOException { target.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { target.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { target.flush(); }
    }

    private static final class Record {
        String name; boolean huge; long crc; long compressed; long size; long offset;
    }

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }

    private static void emit(Counting out, ByteBuffer bytes) throws IOException { out.write(bytes.array(), 0, bytes.position()); }

    /** Writes the whole archive; the caller owns (and closes) {@code target}. */
    public static void write(OutputStream target) throws IOException {
        Counting out = new Counting(target);
        List<Record> records = new ArrayList<>();
        records.add(writeHuge(out));
        records.add(writeStored(out, AFTER_ENTRY, afterBytes()));
        records.add(writeStored(out, HISTORY_ENTRY, HISTORY_JSON.getBytes(StandardCharsets.UTF_8)));

        long directoryOffset = out.count;
        for (Record r : records) writeCentral(out, r);
        long directorySize = out.count - directoryOffset;

        long zip64EndOffset = out.count;
        ByteBuffer end64 = buffer(56);
        end64.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45)
                .putInt(0).putInt(0).putLong(records.size()).putLong(records.size())
                .putLong(directorySize).putLong(directoryOffset);
        emit(out, end64);
        ByteBuffer locator = buffer(20);
        locator.putInt(0x07064b50).putInt(0).putLong(zip64EndOffset).putInt(1);
        emit(out, locator);
        ByteBuffer end = buffer(22);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) records.size()).putShort((short) records.size())
                .putInt((int) directorySize).putInt((int) directoryOffset).putShort((short) 0);
        emit(out, end);
        out.flush();
    }

    private static Record writeHuge(Counting out) throws IOException {
        Record r = new Record();
        r.name = HUGE_ENTRY; r.huge = true; r.offset = out.count;
        byte[] name = HUGE_ENTRY.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = buffer(30 + name.length + 20);
        header.putInt(0x04034b50).putShort((short) 45).putShort((short) (FLAG_DATA_DESCRIPTOR | FLAG_UTF8))
                .putShort((short) 8).putShort((short) DOS_TIME).putShort((short) DOS_DATE)
                .putInt(0).putInt(0).putInt(0).putShort((short) name.length).putShort((short) 20)
                .put(name).putShort((short) 1).putShort((short) 16).putLong(0).putLong(0);
        emit(out, header);

        long dataStart = out.count;
        CRC32 crc = new CRC32();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        byte[] pattern = pattern();
        byte[] compressed = new byte[32 * 1024];
        try {
            long remaining = HUGE_SIZE;
            while (remaining > 0) {
                int length = (int) Math.min(pattern.length, remaining);
                crc.update(pattern, 0, length);
                deflater.setInput(pattern, 0, length);
                while (!deflater.needsInput()) {
                    int produced = deflater.deflate(compressed);
                    if (produced > 0) out.write(compressed, 0, produced);
                }
                remaining -= length;
            }
            deflater.finish();
            while (!deflater.finished()) {
                int produced = deflater.deflate(compressed);
                if (produced > 0) out.write(compressed, 0, produced);
            }
        } finally {
            deflater.end();
        }
        r.crc = crc.getValue(); r.size = HUGE_SIZE; r.compressed = out.count - dataStart;

        ByteBuffer descriptor = buffer(24);
        descriptor.putInt(0x08074b50).putInt((int) r.crc).putLong(r.compressed).putLong(r.size);
        emit(out, descriptor);
        return r;
    }

    private static Record writeStored(Counting out, String entry, byte[] data) throws IOException {
        Record r = new Record();
        r.name = entry; r.offset = out.count; r.size = data.length; r.compressed = data.length;
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        r.crc = crc.getValue();
        byte[] name = entry.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = buffer(30 + name.length);
        header.putInt(0x04034b50).putShort((short) 20).putShort((short) FLAG_UTF8).putShort((short) 0)
                .putShort((short) DOS_TIME).putShort((short) DOS_DATE).putInt((int) r.crc)
                .putInt(data.length).putInt(data.length).putShort((short) name.length).putShort((short) 0)
                .put(name);
        emit(out, header);
        out.write(data, 0, data.length);
        return r;
    }

    private static void writeCentral(Counting out, Record r) throws IOException {
        byte[] name = r.name.getBytes(StandardCharsets.UTF_8);
        int extra = r.huge ? 20 : 0;
        ByteBuffer entry = buffer(46 + name.length + extra);
        entry.putInt(0x02014b50).putShort((short) (r.huge ? 45 : 20)).putShort((short) (r.huge ? 45 : 20))
                .putShort((short) (r.huge ? (FLAG_DATA_DESCRIPTOR | FLAG_UTF8) : FLAG_UTF8))
                .putShort((short) (r.huge ? 8 : 0)).putShort((short) DOS_TIME).putShort((short) DOS_DATE)
                .putInt((int) r.crc)
                .putInt((int) (r.huge ? MAGIC32 : r.compressed)).putInt((int) (r.huge ? MAGIC32 : r.size))
                .putShort((short) name.length).putShort((short) extra).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) r.offset).put(name);
        if (r.huge) entry.putShort((short) 1).putShort((short) 16).putLong(r.size).putLong(r.compressed);
        emit(out, entry);
    }
}
