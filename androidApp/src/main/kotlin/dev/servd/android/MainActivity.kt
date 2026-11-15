package dev.servd.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.servd.core.Servd
import dev.servd.core.update.Update

/**
 * servd's Android control screen - a thin native shell. The real UI is the served web dashboard;
 * this screen only starts/stops the hub (via the foreground service) and shows how to reach it:
 * the share URL, a scan-to-join QR, the cert fingerprint to verify, and the SSH/FTP credentials.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var details: LinearLayout
    private lateinit var qr: ImageView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var openButton: Button
    private lateinit var copyButton: Button
    private lateinit var storageButton: Button
    private lateinit var updateButton: Button
    private var update: Update? = null
    private var updating = false

    private val ink = Color.parseColor("#0F1A1F")
    private val muted = Color.parseColor("#5A6B72")
    private val accent = Color.parseColor("#1F6F5C")
    private val warn = Color.parseColor("#B9770E")

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        render()
        Thread {
            val found = Updater.check(applicationContext) ?: return@Thread
            runOnUiThread {
                update = found
                updateButton.text = "Update to v${found.version}"
                updateButton.visibility = View.VISIBLE
            }
        }.start()
    }

    // ---- in-app update (see Updater; the installer replies to InstallStatusActivity)

    private fun startUpdate() {
        val u = update ?: return
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow servd to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        if (ServdHost.isRunning) {
            AlertDialog.Builder(this)
                .setTitle("Update servd?")
                .setMessage("The hub stops while servd updates. Start it again afterwards.")
                .setPositiveButton("Update") { _, _ -> download(u) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            download(u)
        }
    }

    private fun download(u: Update) {
        updating = true
        updateButton.isEnabled = false
        updateButton.text = "Downloading v${u.version}..."
        Thread {
            val result = runCatching {
                Updater.install(applicationContext, u) { pct ->
                    runOnUiThread { updateButton.text = "Downloading v${u.version}... $pct%" }
                }
            }
            runOnUiThread {
                updating = false
                updateButton.isEnabled = true
                updateButton.text = "Update to v${u.version}"
                result.onFailure { Toast.makeText(this, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ServdHostService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(stateReceiver, filter)
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(stateReceiver) }
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E9EEEF"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = Servd.TAGLINE.replaceFirstChar { it.uppercase() }
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, 0, 0, dp(10))
        })

        statusText = TextView(this).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(statusText)

        // One filled primary per state (Start when stopped, Open dashboard when running); quiet
        // secondaries; the destructive Stop last and visually quieter.
        startButton = button("Start hub", accent, Color.WHITE) { startHub() }
        openButton = button("Open dashboard", accent, Color.WHITE) { openDashboard() }
        copyButton = button("Copy share URL", Color.WHITE, ink) { copyUrl() }
        storageButton = button("Allow file browsing (storage access)", Color.WHITE, ink) { requestAllFilesAccess() }
        stopButton = button("Stop hub", Color.parseColor("#F6E3E2"), Color.parseColor("#B23A3A")) { ServdHostService.stop(this) }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(startButton)
            addView(openButton)
            addView(copyButton)
            addView(storageButton)
            addView(stopButton)
        }
        root.addView(buttons)

        // Connection card (borderless, tonal): scan-to-join QR, then the connection details.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(12))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16); bottomMargin = dp(8) }
        }
        qr = ImageView(this).apply {
            val size = dp(200)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(4); bottomMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
        }
        card.addView(qr)

        // Connection details, rebuilt by render(); the app version is pinned below the card.
        details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(details)
        root.addView(card)

        updateButton = button("Update", Color.WHITE, accent) { startUpdate() }.apply { visibility = View.GONE }
        root.addView(updateButton)

        root.addView(TextView(this).apply {
            text = "v${Servd.VERSION}"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#E9EEEF"))
            addView(root)
            // On edge-to-edge devices (Android 15+) the content draws behind the status and
            // navigation bars; pad by the system-bar insets so nothing is hidden behind them.
            setOnApplyWindowInsetsListener { v, insets ->
                val p = systemBarInsets(insets)
                v.setPadding(p[0], p[1], p[2], p[3])
                insets
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun systemBarInsets(insets: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val s = insets.getInsets(WindowInsets.Type.systemBars())
            intArrayOf(s.left, s.top, s.right, s.bottom)
        } else {
            intArrayOf(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }

    private fun render() {
        val info = ServdHost.info
        val running = ServdHost.isRunning && info != null

        startButton.visibility = if (running) View.GONE else View.VISIBLE
        stopButton.visibility = if (running) View.VISIBLE else View.GONE
        openButton.visibility = if (running) View.VISIBLE else View.GONE
        copyButton.visibility = if (running) View.VISIBLE else View.GONE
        storageButton.visibility = if (running) View.VISIBLE else View.GONE
        storageButton.text =
            if (hasAllFilesAccess()) "Storage access granted (for file browsing)"
            else "Allow file browsing (storage access)"
        storageButton.isEnabled = !hasAllFilesAccess()

        statusText.setTextColor(if (running) accent else muted)
        details.removeAllViews()
        if (info == null || !running) {
            statusText.text = "Hub is stopped"
            qr.visibility = View.GONE
            details.addView(note("Start the hub, then open the dashboard here or scan the QR from another " +
                "device on the same Wi-Fi or hotspot.", top = 0))
            return
        }

        statusText.text = "Hub is running"
        runCatching { qr.setImageBitmap(QrBitmap.of("${info.url}/", dp(200))) }
        qr.visibility = View.VISIBLE

        details.addView(field("Share", "${info.url}/", hint = "scan or open on other devices", size = 15f))
        info.localUrl?.let { details.addView(field("By name", "$it/", hint = "iPhone, Mac, Windows, Linux")) }
        details.addView(field("Admin", "${info.adminUrl}/", hint = "this device only"))
        if (!info.onLan) {
            details.addView(field("Network", "No Wi-Fi or hotspot detected. Reachable on this device only " +
                "until you connect one.", mono = false, color = warn))
        }
        val fp = info.fingerprint.split(":").chunked(8).joinToString("\n") { it.joinToString(":") } // never splits a byte
        details.addView(field("Certificate", fp, hint ="SHA-256, verify on first connect", size = 12f))
        details.addView(pair(field("SSH / SFTP port", "${info.sshPort}"), field("FTPS port", "${info.ftpPort}")))
        details.addView(pair(field("User", info.username), field("Password", info.password)))
        details.addView(note("SSH and FTP start off. Turn them on from the admin dashboard on this device."))
    }

    /** One detail: a small caps label (with an optional hint) over a selectable value. */
    private fun field(
        label: String, value: String, hint: String? = null,
        size: Float = 14f, mono: Boolean = true, color: Int = ink,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, 0)
        addView(TextView(context).apply {
            val sb = SpannableStringBuilder(label.uppercase())
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (hint != null) sb.append("  ·  ").append(hint)
            text = sb
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.04f
        })
        addView(TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            setPadding(0, dp(3), 0, 0)
            setTextIsSelectable(true)
        })
    }

    /** Two details side by side, each taking half the width. */
    private fun pair(left: View, right: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val half = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        addView(left, half())
        addView(right, half())
    }

    private fun note(text: String, top: Int = 14): View = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(top), 0, dp(4))
    }

    private fun startHub() {
        ensureNotificationPermission()
        ServdHostService.start(this)
        Toast.makeText(this, "Starting servd...", Toast.LENGTH_SHORT).show()
    }

    private fun openDashboard() {
        val info = ServdHost.info ?: return
        // The token makes this browser the host; the hub swaps it for a cookie and drops it from the URL.
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${info.adminUrl}/?admin=${info.adminToken}"))) }
            .onFailure { Toast.makeText(this, "No browser to open the dashboard", Toast.LENGTH_SHORT).show() }
    }

    private fun copyUrl() {
        val url = ServdHost.info?.url ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("servd", "$url/"))
        Toast.makeText(this, "Share URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** All-files access lets the served "File browsing" reach the phone's real storage. */
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAllFilesAccess() {
        if (hasAllFilesAccess()) {
            Toast.makeText(this, "Storage access already granted", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: this special permission is granted from a system settings screen.
            val ok = runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }.isSuccess
            if (!ok) runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                2,
            )
        }
    }

    /** Borderless rounded button with a touch ripple (instant feedback) and a 48dp tap target. */
    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(fg)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, Color.red(fg), Color.green(fg), Color.blue(fg))),
                rounded(bg, dp(10)),
                null,
            )
            stateListAnimator = null // flat: no elevation shadow
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
