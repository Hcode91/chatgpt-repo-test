package com.retrotools.cciextractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Adds custom trophies to a PS3 trophy pack, and reads/writes shareable "trophy mods".
 * <p>
 * A new trophy gets the next free id, a line in TROPCONF.SFM, a name/description block in TROP.SFM and every
 * TROP_xx.SFM, and a TROPnnn.PNG icon. It is never linked to the platinum (pid -1), so the game's own platinum
 * still unlocks normally. Works in RPCS3, which doesn't check the SFM signatures; a real PS3 does.
 * <p>
 * A trophy mod is a zip holding only the creator's additions: {@code trophymod.xml} and {@code icons/NNN.png}.
 * It names the trophy set it is for (communication id, trophy count, and a hash of the original trophy list) so it
 * is only applied to the matching pack. Nothing from the game's own pack is included.
 */
public final class Ps3TrophyMod {
    public static final int ICON_SIZE = 240;
    private static final int MAX_ICON_BYTES = 2 << 20, MAX_TROPHIES = 128;

    /** One custom trophy. */
    public static final class Trophy {
        public String name = "", detail = "";
        /** 'B', 'S' or 'G'. Platinum can't be added. */
        public char grade = 'B';
        public boolean hidden;
        public byte[] iconPng;
    }

    public String title = "", author = "";
    public String commId = "", baseHash = "";
    public int baseCount;
    public final List<Trophy> trophies = new ArrayList<>();

    // ------------------------------------------------------------------ editing a pack

    /** A trophy pack held in memory, in file-name order of the original. */
    public static final class Pack {
        public final Map<String, byte[]> files = new LinkedHashMap<>();
        public int version = 1;
        /** Additions made since loading, for export as a mod. */
        public final Ps3TrophyMod added = new Ps3TrophyMod();

        public static Pack load(FileChannel ch) throws IOException {
            Ps3Trophy.Info info = Ps3Trophy.parse(ch);
            Pack p = new Pack();
            p.version = info.version;
            for (Ps3Trophy.FileEntry f : info.files) p.files.put(f.name, Ps3Trophy.read(ch, f));
            p.snapshotBase();
            return p;
        }
        static Pack of(Map<String, byte[]> files, int version) throws IOException {
            Pack p = new Pack();
            p.files.putAll(files); p.version = version;
            p.snapshotBase();
            return p;
        }
        private void snapshotBase() throws IOException {
            String conf = conf();
            added.commId = tag(conf, "npcommid");
            added.baseCount = Ps3TrophyMod.count(conf);
            added.baseHash = listHash(conf, added.baseCount);
        }

        public String conf() throws IOException {
            byte[] d = files.get("TROPCONF.SFM");
            if (d == null) throw new IOException("This pack has no TROPCONF.SFM (encrypted or unusual packs can't be edited)");
            return new String(d, StandardCharsets.UTF_8);
        }
        public int count() throws IOException { return Ps3TrophyMod.count(conf()); }
        public String gameTitle() {
            byte[] d = files.get("TROP.SFM");
            return d == null ? "" : tag(new String(d, StandardCharsets.UTF_8), "title-name");
        }

        /** Adds one trophy and returns its id. */
        public int add(Trophy t) throws IOException {
            if (t.name.trim().isEmpty()) throw new IOException("The trophy needs a name");
            if (t.grade != 'B' && t.grade != 'S' && t.grade != 'G') throw new IOException("Grade must be bronze, silver or gold");
            if (t.iconPng == null || t.iconPng.length < 24 || t.iconPng.length > MAX_ICON_BYTES || (t.iconPng[0] & 255) != 0x89 || t.iconPng[1] != 'P')
                throw new IOException("The trophy needs a PNG icon");
            int id = count();
            if (id >= MAX_TROPHIES) throw new IOException("A trophy set can't have more than " + MAX_TROPHIES + " trophies");
            String attrs = String.format(Locale.ROOT, "<trophy id=\"%03d\" hidden=\"%s\" ttype=\"%c\" pid=\"-1\"", id, t.hidden ? "yes" : "no", t.grade);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String name = e.getKey().toUpperCase(Locale.ROOT);
                if (!name.endsWith(".SFM")) continue;
                String xml = new String(e.getValue(), StandardCharsets.UTF_8);
                String nl = xml.contains("\r\n") ? "\r\n" : "\n";
                String block = name.equals("TROPCONF.SFM") ? " " + attrs + "/>" + nl
                        : " " + attrs + ">" + nl + "  <name>" + escape(t.name.trim()) + "</name>" + nl +
                          "  <detail>" + escape(t.detail.trim()) + "</detail>" + nl + " </trophy>" + nl;
                int end = xml.lastIndexOf("</trophyconf>");
                if (end < 0) throw new IOException(e.getKey() + " isn't a trophy configuration file");
                e.setValue((xml.substring(0, end) + block + xml.substring(end)).getBytes(StandardCharsets.UTF_8));
            }
            files.put(String.format(Locale.ROOT, "TROP%03d.PNG", id), t.iconPng);
            added.trophies.add(t);
            return id;
        }

        /** Applies a mod made for this exact trophy set. */
        public void apply(Ps3TrophyMod mod) throws IOException {
            String conf = conf();
            if (!mod.commId.equals(tag(conf, "npcommid")))
                throw new IOException("This mod is for trophy set " + mod.commId + ", but the pack is " + tag(conf, "npcommid"));
            if (Ps3TrophyMod.count(conf) != mod.baseCount || !listHash(conf, mod.baseCount).equals(mod.baseHash))
                throw new IOException("This mod was made for the original trophy list (" + mod.baseCount + " trophies). The pack has " +
                        Ps3TrophyMod.count(conf) + (Ps3TrophyMod.count(conf) == mod.baseCount ? ", in a different arrangement" : "") +
                        ". Apply it to an unmodified TROPHY.TRP of the same game version.");
            for (Trophy t : mod.trophies) add(t);
            added.title = mod.title; added.author = mod.author;
        }

        public byte[] toTrp() throws IOException {
            List<Ps3Trophy.Source> sources = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                final String n = e.getKey(); final byte[] d = e.getValue();
                sources.add(new Ps3Trophy.Source() {
                    public String name() { return n; }
                    public long size() { return d.length; }
                    public InputStream open() { return new ByteArrayInputStream(d); }
                });
            }
            return Ps3Trophy.build(sources, version);
        }

        /** A progress file for the edited pack, keeping what {@code existing} (may be null) had earned. */
        public byte[] toTropusr(byte[] existing) throws IOException {
            Ps3TrophyProgress old = existing == null ? null : Ps3TrophyProgress.parse(existing);
            return Ps3TrophyProgress.forConfig(conf(), old).toBytes();
        }
    }

    // ------------------------------------------------------------------ mod files

    public byte[] toZip() throws IOException {
        if (trophies.isEmpty()) throw new IOException("There are no added trophies to export");
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trophymod version=\"1\">\n");
        x.append(" <npcommid>").append(escape(commId)).append("</npcommid>\n");
        x.append(" <base-count>").append(baseCount).append("</base-count>\n");
        x.append(" <base-hash>").append(baseHash).append("</base-hash>\n");
        x.append(" <title>").append(escape(title)).append("</title>\n");
        x.append(" <author>").append(escape(author)).append("</author>\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < trophies.size(); i++) {
                Trophy t = trophies.get(i);
                String icon = String.format(Locale.ROOT, "icons/%03d.png", i);
                x.append(" <trophy grade=\"").append(t.grade).append("\" hidden=\"").append(t.hidden ? "yes" : "no")
                        .append("\" icon=\"").append(icon).append("\">\n  <name>").append(escape(t.name)).append("</name>\n  <detail>")
                        .append(escape(t.detail)).append("</detail>\n </trophy>\n");
                zip.putNextEntry(new ZipEntry(icon));
                zip.write(t.iconPng);
                zip.closeEntry();
            }
            x.append("</trophymod>\n");
            zip.putNextEntry(new ZipEntry("trophymod.xml"));
            zip.write(x.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    public static Ps3TrophyMod fromZip(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            long total = 0;
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                for (int n; (n = zip.read(buf)) > 0; ) {
                    total += n;
                    if (b.size() + n > MAX_ICON_BYTES || total > 64L << 20) throw new IOException("The mod file is unreasonably large");
                    b.write(buf, 0, n);
                }
                entries.put(e.getName().replace('\\', '/'), b.toByteArray());
                if (entries.size() > MAX_TROPHIES + 8) throw new IOException("The mod file has too many entries");
            }
        }
        byte[] meta = entries.get("trophymod.xml");
        if (meta == null) throw new IOException("This zip isn't a trophy mod (no trophymod.xml)");
        String xml = new String(meta, StandardCharsets.UTF_8);
        Ps3TrophyMod mod = new Ps3TrophyMod();
        mod.commId = unescape(tag(xml, "npcommid"));
        mod.baseHash = tag(xml, "base-hash");
        mod.title = unescape(tag(xml, "title"));
        mod.author = unescape(tag(xml, "author"));
        try { mod.baseCount = Integer.parseInt(tag(xml, "base-count")); }
        catch (NumberFormatException e) { throw new IOException("The mod's trophy count is unreadable"); }
        Matcher m = Pattern.compile("<trophy\\s([^>]*)>(.*?)</trophy>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            Trophy t = new Trophy();
            String grade = attr(m.group(1), "grade");
            t.grade = grade.isEmpty() ? 'B' : grade.charAt(0);
            t.hidden = attr(m.group(1), "hidden").equals("yes");
            t.name = unescape(tag(m.group(2), "name"));
            t.detail = unescape(tag(m.group(2), "detail"));
            t.iconPng = entries.get(attr(m.group(1), "icon"));
            if (t.iconPng == null) throw new IOException("The mod is missing the icon for \"" + t.name + "\"");
            mod.trophies.add(t);
        }
        if (mod.trophies.isEmpty()) throw new IOException("The mod contains no trophies");
        return mod;
    }

    // ------------------------------------------------------------------ helpers

    static int count(String conf) {
        Matcher m = Pattern.compile("<trophy\\s").matcher(conf);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** SHA-1 over id, grade, hidden and platinum link of the first {@code n} trophies: identifies a trophy list. */
    static String listHash(String conf, int n) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<trophy\\s([^>]*)>").matcher(conf);
        for (int i = 0; i < n && m.find(); i++)
            sb.append(attr(m.group(1), "id")).append(':').append(attr(m.group(1), "ttype")).append(':')
                    .append(attr(m.group(1), "hidden")).append(':').append(attr(m.group(1), "pid")).append(';');
        StringBuilder hex = new StringBuilder();
        for (byte b : Ps3Trophy.sha1(sb.toString().getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }
    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }
    static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    static String unescape(String s) { return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&"); }
}
