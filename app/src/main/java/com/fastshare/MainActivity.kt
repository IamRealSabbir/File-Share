package com.fastshare

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.text.InputType
import android.widget.*
import java.net.*
import java.util.Collections

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvIp: TextView
    private lateinit var tvFolder: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var btnRecv: Button
    private lateinit var bar: ProgressBar
    private var lastDone = 0L
    private var lastT = 0L
    private var lastName = ""
    private var speed = 0.0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("p", 0)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }

    override fun onResume() { super.onResume(); h.post(poll) }
    override fun onPause() { super.onPause(); h.removeCallbacks(poll) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false) =
            TextView(this).apply {
                text = t; textSize = size
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(4))
                root.addView(this)
            }
        fun button(t: String, f: () -> Unit) =
            Button(this).apply { text = t; setOnClickListener { f() }; root.addView(this) }

        label("FastShare", 24f, true)

        label("RECEIVE", 16f, true)
        tvIp = label("My IP: …")
        tvFolder = label("Save folder: not chosen")
        button("Choose save folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 1)
        }
        btnRecv = button("Start receiving") { toggleReceive() }

        label("SEND", 16f, true)
        etIp = EditText(this).apply {
            hint = "Receiver IP (e.g. 192.168.1.5)"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(prefs.getString("ip", ""))
            root.addView(this)
        }
        button("Find receiver automatically") { findReceiver() }
        button("Choose files & send") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 2)
        }
        button("Cancel transfer") {
            startService(Intent(this, TransferService::class.java).setAction("cancel"))
        }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; root.addView(this)
        }
        tvStatus = label("Idle")

        setContentView(ScrollView(this).apply { addView(root) })

        prefs.getString("tree", null)?.let { tvFolder.text = "Save folder: " + Uri.parse(it).lastPathSegment }
    }

    private fun toggleReceive() {
        if (Transfer.receiving) {
            startService(Intent(this, TransferService::class.java).setAction("stoprecv"))
        } else {
            val tree = prefs.getString("tree", null)
            if (tree == null) { toast("Choose a save folder first"); return }
            startForegroundService(Intent(this, TransferService::class.java)
                .setAction("recv").putExtra("tree", tree))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == 1) {
            val uri = data.data ?: return
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("tree", uri.toString()).apply()
            tvFolder.text = "Save folder: " + uri.lastPathSegment
        } else if (req == 2) {
            val uris = ArrayList<Uri>()
            val cd = data.clipData
            if (cd != null) for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            else data.data?.let { uris.add(it) }
            for (u in uris) try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            val host = etIp.text.toString().trim()
            if (host.isEmpty()) { toast("Enter the receiver IP first"); return }
            prefs.edit().putString("ip", host).apply()
            startForegroundService(Intent(this, TransferService::class.java)
                .setAction("send").putExtra("host", host).putParcelableArrayListExtra("uris", uris))
        }
    }

    private fun findReceiver() {
        toast("Searching…")
        Thread {
            try {
                val s = DatagramSocket()
                s.broadcast = true
                s.soTimeout = 2500
                val msg = "FSHARE?".toByteArray()
                val targets = ArrayList<InetAddress>()
                targets.add(InetAddress.getByName("255.255.255.255"))
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces()))
                    if (ni.isUp && !ni.isLoopback)
                        for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
                for (t in targets) try { s.send(DatagramPacket(msg, msg.size, t, Transfer.DISCOVERY_PORT)) } catch (_: Exception) {}
                val buf = ByteArray(64)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val ip = p.address.hostAddress
                val model = String(p.data, 6, p.length - 6)
                runOnUiThread { etIp.setText(ip); toast("Found $model ($ip)") }
            } catch (e: Exception) {
                runOnUiThread { toast("No receiver found. Is it receiving? Type the IP manually.") }
            }
        }.start()
    }

    private fun localIps(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) { emptyList() }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
        else -> String.format("%.1f MB", b / 1048576.0)
    }

    private val poll = object : Runnable {
        override fun run() {
            btnRecv.text = if (Transfer.receiving) "Stop receiving" else "Start receiving"
            tvIp.text = "My IP: " + localIps().joinToString(", ").ifEmpty { "not connected to Wi-Fi" }

            val now = SystemClock.elapsedRealtime()
            val d = Transfer.done
            if (Transfer.fileName != lastName || d < lastDone) {
                lastName = Transfer.fileName; lastDone = d; lastT = now; speed = 0.0
            } else if (now - lastT >= 1000) {
                val inst = (d - lastDone) * 1000.0 / (now - lastT)
                speed = if (speed == 0.0) inst else speed * 0.5 + inst * 0.5
                lastDone = d; lastT = now
            }
            val t = Transfer.total
            bar.progress = if (t > 0) (d * 1000 / t).toInt() else 0
            val sb = StringBuilder(Transfer.status)
            if (Transfer.fileName.isNotEmpty()) {
                sb.append("\n").append(Transfer.fileName)
                if (Transfer.fileCount > 1 && Transfer.sending)
                    sb.append("  (file ${Transfer.fileIndex}/${Transfer.fileCount})")
                sb.append("\n${fmt(d)} / ${fmt(t)}")
                if (speed > 0) {
                    sb.append(String.format("   %.1f MB/s", speed / 1048576.0))
                    val eta = ((t - d) / speed).toLong()
                    sb.append(String.format("   ETA %d:%02d:%02d", eta / 3600, (eta % 3600) / 60, eta % 60))
                }
            }
            tvStatus.text = sb.toString()
            h.postDelayed(this, 500)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
