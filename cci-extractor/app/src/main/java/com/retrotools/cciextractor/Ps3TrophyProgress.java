package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RPCS3's trophy progress file (TROPUSR.DAT), following rpcs3/Loader/TROPUSR.h. Big-endian.
 *
 * <pre>
 * Header 0x30: u32 magic 0x818F54AD, u32 0x00010000, u32 table count (2), u32 0, 32 reserved.
 * Table headers (0x20 each): u32 type, u32 entry size, u32 1, u32 entry count, u64 offset, u64 0.
 * Table 4 (0x60 per entry): u32 4, u32 0x50, u32 index, u32 0, u32 trophy id, u32 grade (1 platinum, 2 gold,
 *   3 silver, 4 bronze), u32 platinum link id (0xFFFFFFFF = none), zeros.
 * Table 6 (0x70 per entry): u32 6, u32 0x60, u32 index, u32 0, u32 trophy id, u32 state (1 = unlocked), u32, u32,
 *   u64 timestamp, u64 timestamp (microseconds since 0001-01-01), zeros.
 * </pre>
 * RPCS3 only creates this file when it is missing, so after trophies are added to a pack the file has to be
 * rebuilt with the new count; {@link #forConfig} does that and keeps what was already earned.
 */
public final class Ps3TrophyProgress {
    public static final int MAGIC = 0x818F54AD;
    /** Microseconds between 0001-01-01 and 1970-01-01 (the PS3 clock's epoch offset). */
    private static final long EPOCH_OFFSET_US = 62135596800000000L;

    public static final class Trophy {
        public int id, grade, pid = -1;
        public boolean unlocked;
        public long timestamp;
    }

    public final List<Trophy> trophies = new ArrayList<>();

    public static boolean isProgress(byte[] d) { return d != null && d.length >= 0x70 && ByteBuffer.wrap(d).getInt() == MAGIC; }

    public static Ps3TrophyProgress parse(byte[] d) throws IOException {
        if (!isProgress(d)) throw new IOException("Not a TROPUSR.DAT file");
        ByteBuffer b = ByteBuffer.wrap(d);
        int tables = b.getInt(8);
        long off4 = -1, off6 = -1;
        int n4 = 0, n6 = 0;
        for (int t = 0; t < tables && 0x30 + t * 0x20 + 0x20 <= d.length; t++) {
            int h = 0x30 + t * 0x20, type = b.getInt(h), count = b.getInt(h + 12);
            long offset = b.getLong(h + 16);
            if (type == 4) { off4 = offset; n4 = count; } else if (type == 6) { off6 = offset; n6 = count; }
        }
        if (off4 < 0 || off6 < 0 || n4 != n6 || n4 < 0 || n4 > 4096 || off4 + n4 * 0x60L > d.length || off6 + n6 * 0x70L > d.length)
            throw new IOException("TROPUSR.DAT tables are damaged");
        Ps3TrophyProgress p = new Ps3TrophyProgress();
        for (int i = 0; i < n4; i++) {
            int e4 = (int) off4 + i * 0x60, e6 = (int) off6 + i * 0x70;
            Trophy t = new Trophy();
            t.id = b.getInt(e4 + 16); t.grade = b.getInt(e4 + 20); t.pid = b.getInt(e4 + 24);
            t.unlocked = b.getInt(e6 + 20) != 0; t.timestamp = b.getLong(e6 + 32);
            p.trophies.add(t);
        }
        return p;
    }

    /**
     * A progress file matching a TROPCONF.SFM (its trophy ids, grades and platinum links), carrying over the
     * unlock state and time of every trophy id that exists in {@code previous} (which may be null).
     */
    public static Ps3TrophyProgress forConfig(String tropconfXml, Ps3TrophyProgress previous) throws IOException {
        Ps3TrophyProgress p = new Ps3TrophyProgress();
        Matcher m = Pattern.compile("<trophy\\s([^>]*)>").matcher(tropconfXml);
        while (m.find()) {
            String attrs = m.group(1);
            Trophy t = new Trophy();
            t.id = Integer.parseInt(attr(attrs, "id", "0"));
            String type = attr(attrs, "ttype", "B");
            t.grade = type.startsWith("P") ? 1 : type.startsWith("G") ? 2 : type.startsWith("S") ? 3 : 4;
            t.pid = Integer.parseInt(attr(attrs, "pid", "-1"));
            if (t.id != p.trophies.size()) throw new IOException("Trophy ids must run 000, 001, 002… without gaps (found " + t.id + ")");
            if (previous != null && t.id < previous.trophies.size()) {
                Trophy old = previous.trophies.get(t.id);
                t.unlocked = old.unlocked; t.timestamp = old.timestamp;
            }
            p.trophies.add(t);
        }
        if (p.trophies.isEmpty()) throw new IOException("No trophies found in TROPCONF.SFM");
        return p;
    }
    private static String attr(String attrs, String name, String fallback) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : fallback;
    }

    public void unlock(int id, long unixMillis) throws IOException {
        Trophy t = get(id);
        if (t.grade == 1) throw new IOException("A platinum unlocks itself once its trophies are earned");
        t.unlocked = true; t.timestamp = unixMillis * 1000 + EPOCH_OFFSET_US;
    }
    public void lock(int id) throws IOException { Trophy t = get(id); t.unlocked = false; t.timestamp = 0; }
    private Trophy get(int id) throws IOException {
        if (id < 0 || id >= trophies.size()) throw new IOException("No trophy with id " + id);
        return trophies.get(id);
    }

    public byte[] toBytes() {
        int n = trophies.size(), off4 = 0x70, off6 = off4 + n * 0x60;
        ByteBuffer b = ByteBuffer.allocate(off6 + n * 0x70);
        b.putInt(MAGIC).putInt(0x00010000).putInt(2).putInt(0);
        b.position(0x30);
        b.putInt(4).putInt(0x50).putInt(1).putInt(n).putLong(off4).putLong(0);
        b.putInt(6).putInt(0x60).putInt(1).putInt(n).putLong(off6).putLong(0);
        for (int i = 0; i < n; i++) {
            Trophy t = trophies.get(i);
            b.position(off4 + i * 0x60);
            b.putInt(4).putInt(0x50).putInt(i).putInt(0).putInt(t.id).putInt(t.grade).putInt(t.pid);
            b.position(off6 + i * 0x70);
            b.putInt(6).putInt(0x60).putInt(i).putInt(0).putInt(t.id).putInt(t.unlocked ? 1 : 0).putInt(0).putInt(0)
                    .putLong(t.timestamp).putLong(t.timestamp);
        }
        return b.array();
    }
}
