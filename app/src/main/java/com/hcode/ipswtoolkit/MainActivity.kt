package com.hcode.ipswtoolkit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import android.view.View
import android.text.InputType
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream
import java.io.InputStream

class MainActivity : Activity() {
    private data class Entry(val name: String, val bytes: Long) {
        override fun toString(): String = name + " (" + if (bytes < 0) "unknown" else (bytes / 1024).toString() + " KiB" + ")"
    }
    private val entries = mutableListOf<Entry>()
    private lateinit var adapter: ArrayAdapter<Entry>
    private lateinit var list: ListView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var key: EditText
    private lateinit var keyButton: Button
    private lateinit var decryptButton: Button
    private lateinit var exportButton: Button
    private var source: Uri? = null
    private var current: Entry? = null
    private var device = ""
    private var version = ""
    private var build = ""
    @Volatile private var busy = false

    companion object {
        const val PICK = 10
        const val RAW = 11
        const val DECRYPT = 12
        const val DEMO_KEY = "c7973558e8f6af22e38d4573737d1533f1d5eec202bf86a32d941975d76f8906c7f0afe4"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(245, 247, 252))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 32, 24, 24)
        scroll.addView(root)
        root.addView(label("IPSW TOOLKIT", 27))
        root.addView(label("Offline iPhone & iPod firmware explorer", 14))
        button("1. Select IPSW firmware", root) { selectIpsw() }
        info = label("No firmware selected", 14)
        root.addView(info)
        root.addView(label("COMPONENTS (tap to select)", 15))
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        list = ListView(this)
        list.adapter = adapter
        list.layoutParams = LinearLayout.LayoutParams(-1, 260 * resources.displayMetrics.density.toInt().coerceAtLeast(1))
        list.setBackgroundColor(Color.WHITE)
        root.addView(list)
        list.setOnItemClickListener { _, _, position, _ ->
            current = entries[position]
            updateControls()
            setStatus("Selected: " + entries[position].name)
        }
        root.addView(label("VFDECRYPT FILESYSTEM KEY (72 HEX CHARACTERS)", 14))
        key = EditText(this)
        key.hint = "Paste public key or your matching firmware key"
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        key.minLines = 2
        root.addView(key)
        keyButton = button("Use published key for iPod 1.1.5", root) {
            key.setText(DEMO_KEY)
            setStatus("Published key loaded locally")
        }
        decryptButton = button("2. Decrypt selected filesystem DMG", root) { chooseOutput(true) }
        exportButton = button("Export selected component unchanged", root) { chooseOutput(false) }
        status = label("Ready. No network access required.", 14)
        root.addView(status)
        root.addView(label("Supports encrypted encrcdsa v2 root DMGs and raw ZIP export. IMG2/IMG3/IMG4, modern AEA, and HFS+ directory browsing are not supported yet.", 12))
        setContentView(scroll)
        updateControls()
    }
    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(28, 48, 76))
        setPadding(0, 7, 0, 7)
    }
    private fun button(title: String, root: LinearLayout, click: () -> Unit): Button {
        val b = Button(this)
        b.text = title
        b.isAllCaps = false
        b.setOnClickListener { click() }
        root.addView(b)
        return b
    }
    private fun setStatus(s: String) { status.text = s }
    private fun updateControls() {
        val available = !busy && source != null && current != null
        exportButton.isEnabled = available
        decryptButton.isEnabled = available && current?.name?.endsWith(".dmg") == true
        keyButton.visibility = if (device == "iPod1,1" && version == "1.1.5" && build == "4B1" &&
                current?.name == "018-3755-1.dmg") View.VISIBLE else View.GONE
    }
    private fun background(message: String, fn: () -> Unit) {
        if (busy) return
        busy = true
        updateControls()
        setStatus(message)
        Thread {
            try { fn() }
            catch (e: Exception) { runOnUiThread { setStatus("Error: " + (e.message ?: "unknown")) } }
            finally { runOnUiThread { busy = false; updateControls() } }
        }.start()
    }
    private fun selectIpsw() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, PICK)
    }
    @Deprecated("Uses standard Activity results for minimal dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK -> {
                source = uri
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { }
                current = null
                entries.clear()
                adapter.notifyDataSetChanged()
                background("Scanning IPSW archive…") { scan(uri) }
            }
            RAW -> export(uri, false)
            DECRYPT -> export(uri, true)
        }
    }
    private fun scan(uri: Uri) {
        val found = mutableListOf<Entry>()
        val props = mutableMapOf<String, String>()
        val input = contentResolver.openInputStream(uri) ?: error("Unable to open IPSW")
        input.use { stream ->
            ZipInputStream(stream.buffered(131072)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) {
                        found.add(Entry(e.name, e.size))
                        if (e.name == "Restore.plist") {
                            val parser = Xml.newPullParser()
                            parser.setInput(zip, "UTF-8")
                            var candidate: String? = null
                            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                                if (parser.eventType == XmlPullParser.START_TAG) {
                                    if (parser.name == "key") candidate = parser.nextText()
                                    else if (parser.name == "string" &&
                                            candidate in setOf("ProductType", "ProductVersion", "ProductBuildVersion")) {
                                        props[candidate!!] = parser.nextText()
                                        candidate = null
                                    }
                                }
                                parser.next()
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(found.isNotEmpty()) { "No entries found in firmware archive" }
        runOnUiThread {
            device = props["ProductType"] ?: "Unknown"
            version = props["ProductVersion"] ?: "Unknown"
            build = props["ProductBuildVersion"] ?: "Unknown"
            entries.clear()
            entries.addAll(found)
            adapter.notifyDataSetChanged()
            current = entries.firstOrNull { it.name == "018-3755-1.dmg" }
                ?: entries.firstOrNull { it.name.endsWith(".dmg") }
            info.text = device + " • OS " + version + " • Build " + build + " • " + found.size + " components"
            setStatus("Firmware scanned. Select a component to decrypt or export.")
            updateControls()
        }
    }
    private fun chooseOutput(decrypt: Boolean) {
        if (busy) return
        val component = current ?: return
        if (decrypt) {
            try { LegacyDmg.keyFromHex(key.text.toString()) }
            catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return }
        }
        val filename = component.name.substringAfterLast('/')
        val outName = if (decrypt) filename.removeSuffix(".dmg") + ".decrypted.dmg" else filename
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, outName)
        startActivityForResult(intent, if (decrypt) DECRYPT else RAW)
    }
    private fun export(target: Uri, decrypt: Boolean) {
        val inputUri = source ?: return
        val entryName = current?.name ?: return
        val hex = key.text.toString()
        background(if (decrypt) "Decrypting…" else "Exporting…") {
            var located = false
            val stream = contentResolver.openInputStream(inputUri) ?: error("Cannot reopen IPSW")
            stream.use { raw ->
                ZipInputStream(raw.buffered(131072)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name == entryName) {
                            located = true
                            (contentResolver.openOutputStream(target, "wt") ?: error("Cannot create output")).use { out ->
                                if (decrypt) {
                                    LegacyDmg.decrypt(zip, out, hex) { pct ->
                                        runOnUiThread { setStatus("Decrypting: " + pct + "%") }
                                    }
                                } else {
                                    val buffer = ByteArray(131072)
                                    while (true) {
                                        val n = zip.read(buffer)
                                        if (n == -1) break
                                        out.write(buffer, 0, n)
                                    }
                                }
                            }
                            break
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(located) { "Selected component no longer exists" }
            runOnUiThread { setStatus(if (decrypt) "Decrypted filesystem saved." else "Firmware component saved.") }
        }
    }
}
