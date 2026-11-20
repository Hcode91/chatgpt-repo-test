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
import java.io.File
import android.provider.OpenableColumns
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

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
    private lateinit var keyStatus: TextView
    private lateinit var modelPicker: Spinner
    private lateinit var firmwarePicker: Spinner
    private var firmwareOptions: List<FirmwareKeys.Record> = emptyList()
    private var customKeys: List<FirmwareKeys.Record> = emptyList()
    private var ignoreSpinnerEvents = false
    private var lastAutoKey = ""
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
        const val IMPORT = 13
        const val DEMO_KEY = "c7973558e8f6af22e38d4573737d1533f1d5eec202bf86a32d941975d76f8906c7f0afe4"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customKeys = loadCustomKeys()

        // Use ONE scrollable container. A ListView nested inside a ScrollView
        // traps touch scrolling and can make parts of the archive inaccessible.
        list = ListView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 252))
            dividerHeight = 1
            isVerticalScrollBarEnabled = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 12)
        }
        header.addView(label("IPSW TOOLKIT", 27))
        header.addView(label("Offline iPhone & iPod firmware explorer", 14))
        button("1. Select IPSW firmware", header) { selectIpsw() }
        info = label("No firmware selected", 14)
        header.addView(info)
        header.addView(label("DEVICE MODEL", 15))
        modelPicker = Spinner(this)
        modelPicker.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Choose an iPhone / iPod model") +
                FirmwareKeys.models.map { it.label + " [" + it.identifier + "]" }
        )
        header.addView(modelPicker)
        header.addView(label("FIRMWARE VERSION / BUILD", 15))
        firmwarePicker = Spinner(this)
        firmwarePicker.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("Select a model first")
        )
        header.addView(firmwarePicker)
        header.addView(label("COMPONENTS (tap to select)", 15))
        // Headers and footers must be registered BEFORE setting the adapter.
        list.addHeaderView(header, null, false)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 32)
        }
        footer.addView(label("VFDECRYPT FILESYSTEM KEY (72 HEX CHARACTERS)", 14))
        keyStatus = label("Select a firmware version and the root filesystem DMG.", 13)
        footer.addView(keyStatus)
        key = EditText(this).apply {
            hint = "Paste public key or your matching firmware key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        footer.addView(key)
        keyButton = button("Reapply matching offline key", footer) { matchKey(force = true) }
        button("Import additional keys from JSON", footer) { selectKeyFile() }
        footer.addView(label("Offline catalogue: " + FirmwareKeys.builtIn.size +
            " historical firmware keys, " + FirmwareKeys.models.size +
            " device variants. Exact firmware and DMG match required.", 12))
        decryptButton = button("2. Decrypt selected filesystem DMG", footer) { chooseOutput(true) }
        exportButton = button("Export selected component unchanged", footer) { chooseOutput(false) }
        status = label("Ready. No network access required.", 14)
        footer.addView(status)
        footer.addView(label("Supports encrypted encrcdsa v2 root DMGs and raw ZIP export. IMG2/IMG3/IMG4, modern AEA, and HFS+ directory browsing are not supported yet.", 12))
        list.addFooterView(footer, null, false)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position - list.headerViewsCount) ?: return@setOnItemClickListener
            current = selected
            matchKey(force = true)
            updateControls()
            setStatus("Selected: " + selected.name)
            Toast.makeText(this, "Selected: " + selected.name.substringAfterLast('/'), Toast.LENGTH_SHORT).show()
        }

        modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignoreSpinnerEvents) return
                val sameDevice = selectedModel() == device
                populateFirmware(if (sameDevice) version else "", if (sameDevice) build else "")
                matchKey(force = true)
            }
        }
        firmwarePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!ignoreSpinnerEvents) matchKey(force = true)
            }
        }
        setContentView(list)
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
        keyButton.isEnabled = matchingRecord() != null
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

    private fun selectedModel(): String =
        FirmwareKeys.models.getOrNull(modelPicker.selectedItemPosition - 1)?.identifier ?: ""

    private fun chosenFirmware(): FirmwareKeys.Record? =
        firmwareOptions.getOrNull(firmwarePicker.selectedItemPosition - 1)

    private fun populateFirmware(selectedVersion: String = "", selectedBuild: String = "") {
        firmwareOptions = FirmwareKeys.forModel(selectedModel(), customKeys)
        val labels = listOf("Select firmware version / build") +
            firmwareOptions.map { it.version + " • " + it.build + " • " + it.filesystem }
        ignoreSpinnerEvents = true
        firmwarePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = firmwareOptions.indexOfFirst {
            it.version == selectedVersion && it.build.equals(selectedBuild, ignoreCase = true)
        }
        firmwarePicker.setSelection(if (index >= 0) index + 1 else 0)
        ignoreSpinnerEvents = false
    }

    private fun matchingRecord(): FirmwareKeys.Record? {
        val candidate = chosenFirmware() ?: return null
        if (source != null && (candidate.model != device ||
                candidate.version != version || !candidate.build.equals(build, ignoreCase = true))) {
            return null
        }
        val file = current?.name?.substringAfterLast('/')
        if (file != null && !candidate.filesystem.equals(file, ignoreCase = true)) return null
        return candidate
    }

    private fun matchKey(force: Boolean = false) {
        val candidate = matchingRecord()
        if (candidate != null) {
            val existing = key.text.toString().trim()
            if (force || existing.isEmpty() || existing == lastAutoKey) key.setText(candidate.key)
            lastAutoKey = candidate.key
            keyStatus.text = "Matched: " + candidate.model + " • " + candidate.version +
                " (" + candidate.build + ") • " + candidate.filesystem +
                if (source == null) "\nSelect the matching IPSW to decrypt." else "\nOffline key filled automatically."
        } else {
            if (lastAutoKey.isNotBlank() && key.text.toString().trim() == lastAutoKey) key.setText("")
            lastAutoKey = ""
            keyStatus.text = if (source == null)
                "Select a model, firmware and IPSW. You may also enter a known key manually."
            else "No exact stored key matches this IPSW/component. Choose the correct build or paste its key."
        }
        keyButton.isEnabled = candidate != null
    }

    private fun selectKeyFile() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, IMPORT)
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
                device = ""; version = ""; build = ""
                key.setText("")
                lastAutoKey = ""
                background("Scanning IPSW archive…") { scan(uri) }
            }
            RAW -> export(uri, false)
            DECRYPT -> export(uri, true)
            IMPORT -> importKeys(uri)
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
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        } ?: ""
        val filename = Regex("""(iPhone\d+,\d+|iPod\d+,\d+)_([0-9.]+)_([a-zA-Z0-9]+)_Restore\.ipsw""",
            RegexOption.IGNORE_CASE).find(name)
        runOnUiThread {
            device = props["ProductType"] ?: filename?.groupValues?.get(1) ?: "Unknown"
            version = props["ProductVersion"] ?: filename?.groupValues?.get(2) ?: "Unknown"
            build = props["ProductBuildVersion"] ?: filename?.groupValues?.get(3) ?: "Unknown"
            entries.clear()
            entries.addAll(found)
            adapter.notifyDataSetChanged()
            ignoreSpinnerEvents = true
            val modelIndex = FirmwareKeys.models.indexOfFirst { it.identifier == device }
            modelPicker.setSelection(if (modelIndex >= 0) modelIndex + 1 else 0)
            ignoreSpinnerEvents = false
            populateFirmware(version, build)
            val candidate = chosenFirmware()
            current = entries.firstOrNull {
                candidate != null && it.name.substringAfterLast('/').equals(candidate.filesystem, true)
            } ?: entries.firstOrNull { it.name.endsWith(".dmg", true) }
            matchKey()
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
            if (chosenFirmware() != null && matchingRecord() == null) {
                Toast.makeText(this, "The chosen firmware key does not match the loaded IPSW or selected DMG. Use the correct firmware build.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val filename = component.name.substringAfterLast('/')
        val outName = if (decrypt) filename.removeSuffix(".dmg") + ".decrypted.dmg" else filename
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, outName)
        startActivityForResult(intent, if (decrypt) DECRYPT else RAW)
    }

    private fun parseKeyRecords(json: String): List<FirmwareKeys.Record> {
        val rows = JSONObject(json).getJSONArray("keys")
        require(rows.length() <= 500) { "Maximum 500 keys per file" }
        val result = mutableListOf<FirmwareKeys.Record>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val model = row.getString("model").trim()
            val version = row.getString("version").trim()
            val build = row.getString("build").trim()
            val file = row.getString("filesystem").trim()
            val secret = row.getString("key").trim().lowercase(Locale.ROOT)
            require(FirmwareKeys.models.any { it.identifier == model }) { "Unknown model: " + model }
            require(version.length in 1..25 && build.length in 1..25 && file.length in 5..100 &&
                file.endsWith(".dmg", true) && '/' !in file && '\\' !in file) {
                "Invalid version/build/root filesystem filename"
            }
            require(secret.length == 72 && secret.all { it in "0123456789abcdef" }) {
                "Invalid key length or characters at record " + (i + 1)
            }
            result.add(FirmwareKeys.Record(model, version, build, file, secret, "User imported"))
        }
        return result.distinctBy { it.identity }
    }

    private fun loadCustomKeys(): List<FirmwareKeys.Record> = try {
        val file = File(filesDir, "user-rootfs-keys.json")
        if (file.exists()) parseKeyRecords(file.readText()) else emptyList()
    } catch (_: Exception) { emptyList() }

    private fun importKeys(uri: Uri) {
        background("Importing keys…") {
            val json = contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Key file too large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            } ?: error("Unable to open JSON")
            val incoming = parseKeyRecords(json)
            require(incoming.isNotEmpty()) { "No keys found in JSON" }
            val merged = (customKeys + incoming).distinctBy { it.identity }.takeLast(500)
            val arr = JSONArray()
            for (k in merged) {
                arr.put(JSONObject().apply {
                    put("model", k.model); put("version", k.version); put("build", k.build)
                    put("filesystem", k.filesystem); put("key", k.key)
                })
            }
            File(filesDir, "user-rootfs-keys.json").writeText(JSONObject().put("keys", arr).toString())
            runOnUiThread {
                customKeys = merged
                populateFirmware(version, build)
                matchKey()
                setStatus("Imported " + incoming.size + " keys. Stored offline on this device.")
            }
        }
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
