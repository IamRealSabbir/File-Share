package com.fastshare

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class TransferService : Service() {
    private val pool = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val sockets = ConcurrentHashMap<String, Socket>()
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var treeUri: Uri? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private lateinit var nm: NotificationManager

    private val ticker = object : Runnable {
        override fun run() {
            if (!Transfer.receiving && !Transfer.sending) return
            nm.notify(1, notif())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fs", "Transfers", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif())
        acquireLocks()
        when (i?.action) {
            "recv" -> startReceiving(Uri.parse(i.getStringExtra("tree")))
            "stoprecv" -> stopReceiving()
            "send" -> {
                @Suppress("DEPRECATION")
                val uris = i.getParcelableArrayListExtra<Uri>("uris") ?: arrayListOf()
                startSending(i.getStringExtra("host") ?: "", uris)
            }
            "cancel" -> Transfer.cancel = true
        }
        handler.removeCallbacks(ticker); handler.post(ticker)
        checkStop()
        return START_NOT_STICKY
    }

    private fun notif(): Notification {
        val pct = if (Transfer.total > 0) (Transfer.done * 100 / Transfer.total).toInt() else 0
        val text = if (Transfer.fileName.isEmpty()) Transfer.status else "${Transfer.fileName}  $pct%"
        return Notification.Builder(this, "fs")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FastShare")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fastshare:wl").apply { acquire() }
        }
        if (wifi == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "fastshare:wifi").apply { acquire() }
        }
    }

    private fun checkStop() {
        if (!Transfer.receiving && !Transfer.sending) {
            handler.removeCallbacks(ticker)
            wake?.let { if (it.isHeld) it.release() }; wake = null
            wifi?.let { if (it.isHeld) it.release() }; wifi = null
            stopForeground(true)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- RECEIVE

    private fun startReceiving(tree: Uri) {
        treeUri = tree
        if (Transfer.receiving) return
        Transfer.receiving = true
        Transfer.status = "Waiting for sender…"
        pool.execute { runServer() }
        pool.execute { runDiscovery() }
    }

    private fun stopReceiving() {
        Transfer.receiving = false
        Transfer.status = "Receiver stopped"
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        sockets.values.forEach { try { it.close() } catch (_: Exception) {} }
        checkStop()
    }

    private fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.receiveBufferSize = 1 shl 21
            ss.bind(InetSocketAddress(Transfer.PORT))
            server = ss
            while (Transfer.receiving) {
                val s = ss.accept()
                pool.execute { handle(s) }
            }
        } catch (e: Exception) {
            if (Transfer.receiving) {
                Transfer.status = "Server error: ${e.message}"
                Transfer.receiving = false
                handler.post { checkStop() }
            }
        }
    }

    private fun runDiscovery() {
        try {
            val u = DatagramSocket(null)
            u.reuseAddress = true
            u.bind(InetSocketAddress(Transfer.DISCOVERY_PORT))
            udp = u
            val buf = ByteArray(64)
            while (Transfer.receiving) {
                val p = DatagramPacket(buf, buf.size)
                u.receive(p)
                if (String(p.data, 0, p.length) == "FSHARE?") {
                    val r = "FSHARE!${Build.MODEL}".toByteArray()
                    u.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        } catch (_: Exception) {}
    }

    private fun handle(s: Socket) {
        var key: String? = null
        var lock: ReentrantLock? = null
        try {
            s.soTimeout = 30000
            s.keepAlive = true
            val din = DataInputStream(s.getInputStream())
            val dout = DataOutputStream(s.getOutputStream())
            if (din.readInt() != Transfer.MAGIC) return
            val name = din.readUTF().replace('/', '_').replace('\\', '_')
            val size = din.readLong()
            key = "$name|$size"

            // A reconnect for the same file kicks out any stale connection first.
            sockets.put(key, s)?.let { try { it.close() } catch (_: Exception) {} }
            lock = locks.getOrPut(key) { ReentrantLock() }
            lock.lock()

            val tree = DocumentFile.fromTreeUri(this, treeUri ?: return) ?: throw IOException("No folder")
            val existing = tree.findFile(name)
            if (existing != null && existing.isFile && existing.length() == size) {
                dout.writeLong(-1); dout.flush()
                Transfer.status = "Already have $name"
                return
            }
            val partName = "$name.$size.part"
            var part = tree.findFile(partName)
            var offset = 0L
            if (part != null) {
                offset = part.length()
                if (offset > size) { part.delete(); part = null; offset = 0 }
            }
            if (part == null) {
                part = tree.createFile("application/octet-stream", partName) ?: throw IOException("Cannot create file")
            }
            dout.writeLong(offset); dout.flush()

            Transfer.fileName = name; Transfer.total = size; Transfer.done = offset
            Transfer.status = if (offset > 0) "Resuming…" else "Receiving…"

            val os = contentResolver.openOutputStream(part.uri, if (offset > 0) "wa" else "wt")
                ?: throw IOException("Cannot open file")
            os.use { o ->
                val buf = ByteArray(1 shl 20)
                var got = offset
                while (got < size) {
                    val n = din.read(buf, 0, minOf(buf.size.toLong(), size - got).toInt())
                    if (n < 0) throw EOFException("Sender disconnected")
                    o.write(buf, 0, n)
                    got += n
                    Transfer.done = got
                }
                o.flush()
            }
            existing?.delete() // same name, different size -> replace
            if (!part.renameTo(name)) throw IOException("Rename failed")
            dout.writeByte(1); dout.flush()
            Transfer.status = "Received $name ✓"
        } catch (e: Exception) {
            if (Transfer.receiving) Transfer.status = "Interrupted (${e.message}) – will resume"
        } finally {
            lock?.let { if (it.isHeldByCurrentThread) it.unlock() }
            if (key != null) sockets.remove(key, s)
            try { s.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------- SEND

    private fun startSending(host: String, uris: List<Uri>) {
        if (Transfer.sending || host.isEmpty() || uris.isEmpty()) return
        Transfer.sending = true
        Transfer.cancel = false
        Transfer.fileCount = uris.size
        pool.execute {
            try {
                for ((i, u) in uris.withIndex()) {
                    if (Transfer.cancel) break
                    Transfer.fileIndex = i + 1
                    sendOne(host, u)
                }
                Transfer.status = if (Transfer.cancel) "Cancelled" else "All files sent ✓"
            } catch (e: Exception) {
                Transfer.status = "Failed: ${e.message}"
            } finally {
                Transfer.sending = false
                handler.post { checkStop() }
            }
        }
    }

    private fun sendOne(host: String, uri: Uri) {
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) throw IOException("Cannot read size of $name")
        Transfer.fileName = name; Transfer.total = size; Transfer.done = 0

        var attempt = 0
        while (!Transfer.cancel) {
            try {
                transferOnce(host, uri, name, size)
                return
            } catch (e: IOException) {
                attempt++
                if (attempt > 300) throw e
                Transfer.status = "Connection lost – retrying ($attempt)…"
                Thread.sleep(2000)
            }
        }
    }

    private fun transferOnce(host: String, uri: Uri, name: String, size: Long) {
        val s = Socket()
        try {
            s.sendBufferSize = 1 shl 21
            s.keepAlive = true
            s.connect(InetSocketAddress(host, Transfer.PORT), 5000)
            s.soTimeout = 30000
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            out.writeInt(Transfer.MAGIC); out.writeUTF(name); out.writeLong(size); out.flush()

            val offset = inp.readLong()
            if (offset < 0) {
                Transfer.done = size
                Transfer.status = "Skipped (receiver already has it): $name"
                return
            }
            Transfer.done = offset
            Transfer.status = if (offset > 0) "Resuming from ${offset shr 20} MB…" else "Sending…"

            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $name")
            pfd.use {
                val fis = FileInputStream(it.fileDescriptor)
                fis.channel.position(offset)
                val buf = ByteArray(1 shl 20)
                var sent = offset
                while (sent < size) {
                    if (Transfer.cancel) return
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                    if (n < 0) throw IOException("File ended early")
                    out.write(buf, 0, n)
                    sent += n
                    Transfer.done = sent
                }
                out.flush()
            }
            if (inp.read() != 1) throw IOException("No confirmation from receiver")
            Transfer.status = "Sent $name ✓"
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
