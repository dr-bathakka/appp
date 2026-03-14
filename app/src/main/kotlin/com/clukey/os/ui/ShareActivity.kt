package com.clukey.os.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShareActivity"
        private const val REQ_PICK_FILE = 200
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private lateinit var tvClipboard: TextView
    private lateinit var etSendText: EditText
    private lateinit var tvStatus: TextView
    private lateinit var fileListBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tabClip: TextView
    private lateinit var tabFiles: TextView
    private lateinit var panelClip: View
    private lateinit var panelFiles: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsManager.init(applicationContext)
        setBackgroundColor(window, 0xFF0A0E1A.toInt())
        window.statusBarColor = 0xFF0A0E1A.toInt()
        setContentView(buildUI())
        handleIncomingShare()
        loadClipboardFromServer()
        loadFileList()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Handle share intent from other apps ───────────────────────────────────
    private fun handleIncomingShare() {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri  = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            when {
                text != null -> {
                    etSendText.setText(text)
                    switchTab(true)
                    setStatus("📋 Text ready to send to PC/server", 0xFF4ADE80.toInt())
                }
                uri != null -> {
                    switchTab(false)
                    uploadFile(uri)
                }
            }
        }
    }

    // ── Main UI ───────────────────────────────────────────────────────────────
    private fun buildUI(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0xFF0A0E1A.toInt()) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }

        // Header
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            addView(TextView(ctx).apply {
                text = "←"
                textSize = 20f
                setTextColor(0xFF4FC3F7.toInt())
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { finish() }
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "SHARE"
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFE2E8F0.toInt())
                    letterSpacing = 0.2f
                })
                addView(TextView(ctx).apply {
                    text = "PHONE ↔ PC ↔ SERVER"
                    textSize = 9f
                    setTextColor(0xFF4FC3F7.toInt())
                    letterSpacing = 0.3f
                })
            })
        })

        // Status
        tvStatus = TextView(ctx).apply {
            text = "● Ready"
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(tvStatus)

        // Tabs
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        tabClip = tabButton(ctx, "📋  CLIPBOARD", true) { switchTab(true) }
        tabFiles = tabButton(ctx, "📁  FILES", false) { switchTab(false) }
        tabRow.addView(tabClip)
        tabRow.addView(tabFiles)
        root.addView(tabRow)

        // ── CLIPBOARD PANEL ──
        panelClip = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL

            // Phone clipboard → Server/PC
            addView(cardLabel(ctx, "SEND TO SERVER & PC"))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF111827.toInt())
                setPadding(dp(14), dp(14), dp(14), dp(14))

                etSendText = EditText(ctx).apply {
                    hint = "Type or paste text to send to your PC..."
                    textSize = 13f
                    setTextColor(0xFFE2E8F0.toInt())
                    setHintTextColor(0xFF475569.toInt())
                    setBackgroundColor(0xFF1E293B.toInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    minLines = 3
                    gravity = Gravity.TOP
                }
                addView(etSendText)

                val btnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                btnRow.addView(actionBtn(ctx, "📋 PASTE FROM PHONE", 0xFF1E3A5F.toInt(), 0xFF4FC3F7.toInt()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    etSendText.setText(text)
                    setStatus("Pasted from phone clipboard", 0xFF4FC3F7.toInt())
                })
                btnRow.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                })
                btnRow.addView(actionBtn(ctx, "🖥 SEND TO PC", 0xFF1E3A2A.toInt(), 0xFF4ADE80.toInt()) {
                    sendClipboardToPc()
                })
                addView(btnRow)
            })

            // Server clipboard → Phone
            addView(cardLabel(ctx, "GET FROM SERVER / PC"))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF111827.toInt())
                setPadding(dp(14), dp(14), dp(14), dp(14))

                tvClipboard = TextView(ctx).apply {
                    text = "Loading..."
                    textSize = 13f
                    setTextColor(0xFF94A3B8.toInt())
                    setBackgroundColor(0xFF1E293B.toInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    minLines = 3
                }
                addView(tvClipboard)

                val btnRow2 = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                btnRow2.addView(actionBtn(ctx, "🔄 REFRESH", 0xFF1E293B.toInt(), 0xFF94A3B8.toInt()) {
                    loadClipboardFromServer()
                })
                btnRow2.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                })
                btnRow2.addView(actionBtn(ctx, "📋 COPY TO PHONE", 0xFF1E3A5F.toInt(), 0xFF4FC3F7.toInt()) {
                    copyServerClipToPhone()
                })
                btnRow2.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                })
                btnRow2.addView(actionBtn(ctx, "🖥 GET FROM PC", 0xFF1E3A2A.toInt(), 0xFF4ADE80.toInt()) {
                    getClipboardFromPc()
                })
                addView(btnRow2)
            })
        }

        // ── FILES PANEL ──
        panelFiles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL

            // Upload phone → server
            addView(cardLabel(ctx, "PHONE → SERVER"))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF111827.toInt())
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(TextView(ctx).apply {
                    text = "Pick a file from your phone to upload to the CluKey server.\nYou can then download it on PC from the dashboard."
                    textSize = 11f
                    setTextColor(0xFF64748B.toInt())
                    setPadding(0, 0, 0, dp(10))
                })

                progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    visibility = View.GONE
                    max = 100
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).also {
                        it.bottomMargin = dp(8)
                    }
                }
                addView(progressBar)

                addView(actionBtn(ctx, "📁  PICK FILE & UPLOAD", 0xFF1E3A5F.toInt(), 0xFF4FC3F7.toInt()) {
                    pickFile()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            })

            // Server files → download to phone
            addView(cardLabel(ctx, "SERVER FILES → PHONE"))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF111827.toInt())
                setPadding(dp(14), dp(14), dp(14), dp(14))

                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(10))
                    addView(TextView(ctx).apply {
                        text = "Files on server:"
                        textSize = 11f
                        setTextColor(0xFF64748B.toInt())
                        val lp = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = lp
                    })
                    addView(actionBtn(ctx, "🔄", 0xFF1E293B.toInt(), 0xFF94A3B8.toInt()) {
                        loadFileList()
                    })
                })

                fileListBox = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(fileListBox)
            })
        }

        panelFiles.visibility = View.GONE
        root.addView(panelClip)
        root.addView(panelFiles)
        scroll.addView(root)
        return scroll
    }

    // ── TABS ──────────────────────────────────────────────────────────────────
    private fun switchTab(isClip: Boolean) {
        panelClip.visibility  = if (isClip)  View.VISIBLE else View.GONE
        panelFiles.visibility = if (!isClip) View.VISIBLE else View.GONE
        tabClip.setBackgroundColor(if (isClip)  0xFF1E3A5F.toInt() else 0xFF111827.toInt())
        tabClip.setTextColor(if (isClip)  0xFF4FC3F7.toInt() else 0xFF64748B.toInt())
        tabFiles.setBackgroundColor(if (!isClip) 0xFF1E3A5F.toInt() else 0xFF111827.toInt())
        tabFiles.setTextColor(if (!isClip) 0xFF4FC3F7.toInt() else 0xFF64748B.toInt())
    }

    // ── CLIPBOARD ACTIONS ─────────────────────────────────────────────────────
    private fun loadClipboardFromServer() {
        setStatus("⟳ Loading clipboard from server...", 0xFF64748B.toInt())
        scope.launch(Dispatchers.IO) {
            try {
                val url = "${PrefsManager.serverUrl.trimEnd('/')}/clipboard"
                val req = Request.Builder().url(url)
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey).build()
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: "{}"
                val json = JSONObject(body)
                val clip = json.optJSONObject("clipboard")
                val text = clip?.optString("text", "") ?: json.optString("text", "")
                withContext(Dispatchers.Main) {
                    tvClipboard.text = if (text.isBlank()) "(empty)" else text
                    tvClipboard.setTextColor(
                        if (text.isBlank()) 0xFF475569.toInt() else 0xFFE2E8F0.toInt())
                    setStatus("● Clipboard loaded", 0xFF4ADE80.toInt())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvClipboard.text = "Could not reach server"
                    setStatus("✗ ${e.message}", 0xFFF87171.toInt())
                }
            }
        }
    }

    private fun sendClipboardToPc() {
        val text = etSendText.text.toString()
        if (text.isBlank()) { setStatus("Nothing to send", 0xFFFBBF24.toInt()); return }
        setStatus("⟳ Sending to server & PC...", 0xFF64748B.toInt())
        scope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("text", text); put("source", "phone")
                }.toString().toRequestBody("application/json".toMediaType())
                val url = "${PrefsManager.serverUrl.trimEnd('/')}/clipboard"
                val req = Request.Builder().url(url)
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey)
                    .post(body).build()
                val resp = http.newCall(req).execute()
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        setStatus("✅ Sent to server — PC will sync automatically", 0xFF4ADE80.toInt())
                    } else {
                        setStatus("✗ Server error: ${resp.code}", 0xFFF87171.toInt())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("✗ Failed: ${e.message}", 0xFFF87171.toInt())
                }
            }
        }
    }

    private fun copyServerClipToPhone() {
        val text = tvClipboard.text.toString()
        if (text.isBlank() || text == "(empty)") {
            setStatus("Nothing to copy", 0xFFFBBF24.toInt()); return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("CluKey", text))
        setStatus("✅ Copied to phone clipboard!", 0xFF4ADE80.toInt())
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }

    private fun getClipboardFromPc() {
        setStatus("⟳ Getting clipboard from PC...", 0xFF64748B.toInt())
        scope.launch(Dispatchers.IO) {
            try {
                val url = "${PrefsManager.serverUrl.trimEnd('/')}/pc/clipboard"
                val req = Request.Builder().url(url)
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey).get().build()
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: "{}"
                val json = JSONObject(body)
                val text = json.optString("text", "")
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        tvClipboard.text = text
                        tvClipboard.setTextColor(0xFFE2E8F0.toInt())
                        setStatus("✅ Got clipboard from PC", 0xFF4ADE80.toInt())
                    } else {
                        setStatus("PC clipboard is empty or PC is offline", 0xFFFBBF24.toInt())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("✗ PC offline: ${e.message}", 0xFFF87171.toInt())
                }
            }
        }
    }

    // ── FILE ACTIONS ──────────────────────────────────────────────────────────
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uploadFile(it) }
        }
    }

    private fun uploadFile(uri: Uri) {
        val fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
        setStatus("⟳ Uploading $fileName...", 0xFF64748B.toInt())
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        scope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Cannot read file")
                withContext(Dispatchers.Main) { progressBar.progress = 30 }

                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                        bytes.toRequestBody(mimeType.toMediaType()))
                    .build()

                withContext(Dispatchers.Main) { progressBar.progress = 60 }

                val url = "${PrefsManager.serverUrl.trimEnd('/')}/files/upload"
                val req = Request.Builder().url(url)
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey)
                    .post(reqBody).build()
                val resp = http.newCall(req).execute()

                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    progressBar.visibility = View.GONE
                    if (resp.isSuccessful) {
                        setStatus("✅ $fileName uploaded! Download from PC dashboard.", 0xFF4ADE80.toInt())
                        loadFileList()
                    } else {
                        setStatus("✗ Upload failed: ${resp.code}", 0xFFF87171.toInt())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    setStatus("✗ Upload failed: ${e.message}", 0xFFF87171.toInt())
                }
            }
        }
    }

    private fun loadFileList() {
        scope.launch(Dispatchers.IO) {
            try {
                val url = "${PrefsManager.serverUrl.trimEnd('/')}/files"
                val req = Request.Builder().url(url)
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey).build()
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: "{}"
                val json = JSONObject(body)
                val files = json.optJSONArray("files")
                withContext(Dispatchers.Main) {
                    fileListBox.removeAllViews()
                    if (files == null || files.length() == 0) {
                        fileListBox.addView(TextView(this@ShareActivity).apply {
                            text = "No files on server"
                            textSize = 12f
                            setTextColor(0xFF475569.toInt())
                            setPadding(0, dp(8), 0, dp(8))
                        })
                        return@withContext
                    }
                    for (i in 0 until files.length()) {
                        val file = files.getJSONObject(i)
                        val name = file.optString("name")
                        val size = file.optString("size_str", "")
                        fileListBox.addView(buildFileRow(name, size))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("✗ Cannot load files: ${e.message}", 0xFFF87171.toInt())
                }
            }
        }
    }

    private fun buildFileRow(name: String, size: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(2)
            layoutParams = p

            val icon = when {
                name.endsWith(".jpg") || name.endsWith(".png") -> "🖼"
                name.endsWith(".pdf")  -> "📄"
                name.endsWith(".mp4") || name.endsWith(".mkv") -> "🎬"
                name.endsWith(".mp3") || name.endsWith(".m4a") -> "🎵"
                name.endsWith(".zip") -> "📦"
                name.endsWith(".apk") -> "📱"
                else -> "📄"
            }

            addView(TextView(this@ShareActivity).apply {
                text = icon
                textSize = 16f
                setPadding(0, 0, dp(10), 0)
            })
            addView(LinearLayout(this@ShareActivity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                addView(TextView(this@ShareActivity).apply {
                    text = name
                    textSize = 12f
                    setTextColor(0xFFCBD5E1.toInt())
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                addView(TextView(this@ShareActivity).apply {
                    text = size
                    textSize = 10f
                    setTextColor(0xFF475569.toInt())
                })
            })
            addView(actionBtn(this@ShareActivity, "⬇ GET", 0xFF0D3A1A.toInt(), 0xFF4ADE80.toInt()) {
                downloadFile(name)
            })
        }
    }

    private fun downloadFile(name: String) {
        setStatus("⟳ Downloading $name...", 0xFF64748B.toInt())
        scope.launch(Dispatchers.IO) {
            try {
                val url = "${PrefsManager.serverUrl.trimEnd('/')}/files/download/$name"
                val req = Request.Builder().url(url)
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey).build()
                val resp = http.newCall(req).execute()
                val bytes = resp.body?.bytes() ?: throw Exception("Empty response")

                // Save to Downloads folder
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val outFile = java.io.File(downloadsDir, name)
                outFile.writeBytes(bytes)

                // Notify media scanner
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(outFile)))

                withContext(Dispatchers.Main) {
                    setStatus("✅ Saved to Downloads/$name", 0xFF4ADE80.toInt())
                    Toast.makeText(this@ShareActivity,
                        "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("✗ Download failed: ${e.message}", 0xFFF87171.toInt())
                }
            }
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────
    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
        }
        return name ?: uri.lastPathSegment
    }

    private fun setStatus(msg: String, color: Int) {
        tvStatus.text = msg
        tvStatus.setTextColor(color)
    }

    private fun tabButton(ctx: Context, text: String, active: Boolean,
                          onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(if (active) 0xFF1E3A5F.toInt() else 0xFF111827.toInt())
            setTextColor(if (active) 0xFF4FC3F7.toInt() else 0xFF64748B.toInt())
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun cardLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 9f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFF4FC3F7.toInt())
        letterSpacing = 0.3f
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun actionBtn(ctx: Context, text: String, bg: Int, fg: Int,
                          onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(bg)
            setTextColor(fg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun setBackgroundColor(window: android.view.Window, color: Int) {}
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
