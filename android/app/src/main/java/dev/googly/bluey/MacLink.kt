package dev.googly.bluey

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

/** Finds the Mac app on the local network with Bonjour (Android's NSD) and keeps a connection open. */
class MacLink(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("googly", Context.MODE_PRIVATE)
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var connected by mutableStateOf(false)
        private set
    var macName by mutableStateOf<String?>(null)
        private set
    /** Every Mac running Googly Eyes on this Wi-Fi, by its Bonjour name. */
    var macs by mutableStateOf<List<String>>(emptyList())
        private set
    /** The Mac the phone is linked to (or trying to link to). */
    var currentMac by mutableStateOf<String?>(null)
        private set
    /** The Mac picked in the speaker panel. Remembered, so the phone goes back to it on launch. */
    var preferredMac by mutableStateOf(prefs.getString("preferredMac", null))
        private set

    /** Commands from the Mac, like "wake" and "sleep", and what it heard and said when it's the brain. */
    var onCommand: ((Packet) -> Unit)? = null
    var onFace: ((FaceState) -> Unit)? = null
    /** The link to the Mac dropped (it quit, restarted, or left the Wi-Fi). */
    var onDisconnected: (() -> Unit)? = null

    private val waiting = HashMap<String, (Packet?) -> Unit>()
    private val found = LinkedHashMap<String, NsdServiceInfo>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var link: LineConnection? = null
    private var resolving = false
    /** Bumped on every stop, so late answers from an old search are ignored. */
    private var generation = 0
    private val retry = Runnable { if (discovery == null) start() else connectIfNeeded() }

    fun start() {
        if (discovery != null) return
        multicastLock = wifi?.createMulticastLock("googly")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("Googly", "Bonjour search failed: $errorCode")
                main.post {
                    if (discovery === this) {
                        discovery = null
                        restart()
                    }
                }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (discovery !== this) return@post
                    found[unescape(info.serviceName)] = info
                    refresh()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (discovery !== this) return@post
                    found.remove(unescape(info.serviceName))
                    refresh()
                }
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(GooglyService.TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w("Googly", "Bonjour search couldn't start: $e")
            discovery = null
            scheduleRetry()
        }
    }

    fun stop() {
        generation++
        main.removeCallbacks(retry)
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
        found.clear()
        macs = emptyList()
        resolving = false
        link?.cancel()
        link = null
        connected = false
        failWaiting()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun restart() {
        stop()
        scheduleRetry()
    }

    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        main.postDelayed(retry, 1500)
    }

    private fun refresh() {
        macs = found.keys.sorted()
        // Move over when the picked Mac shows up while linked to a different one.
        val preferred = preferredMac
        if (preferred != null && currentMac != preferred && preferred in found) link?.cancel()
        connectIfNeeded()
    }

    /** Switches to another Mac and remembers the choice. */
    fun choose(name: String) {
        preferredMac = name
        prefs.edit().putString("preferredMac", name).apply()
        if (currentMac != name) link?.cancel()
        connectIfNeeded()
    }

    private fun connectIfNeeded() {
        if (link != null || resolving || found.isEmpty()) return
        val name = preferredMac?.takeIf { it in found } ?: found.keys.first()
        val info = found.getValue(name)
        currentMac = name
        resolving = true
        val gen = generation
        resolve(info) { host, port ->
            if (gen != generation) return@resolve
            resolving = false
            if (host == null) {
                scheduleRetry()
                return@resolve
            }
            val preferred = preferredMac
            if (preferred != null && preferred != name && preferred in found) {
                connectIfNeeded()  // they picked another Mac while this one was being looked up
                return@resolve
            }
            open(host, port)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, done: (InetAddress?, Int) -> Unit) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w("Googly", "Couldn't resolve ${serviceInfo.serviceName}: $errorCode")
                    main.post { done(null, 0) }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) {
                        serviceInfo.hostAddresses.firstOrNull { it is Inet4Address } ?: serviceInfo.hostAddresses.firstOrNull()
                    } else {
                        serviceInfo.host
                    }
                    main.post { done(host, serviceInfo.port) }
                }
            })
        } catch (e: Exception) {
            Log.w("Googly", "Resolve failed: $e")
            main.post { done(null, 0) }
        }
    }

    private fun open(host: InetAddress, port: Int) {
        val conn = LineConnection(host, port)
        conn.onReady = {
            if (conn === link) {
                connected = true
                conn.send(Packet(hello = deviceName))
            }
        }
        conn.onClosed = {
            if (conn === link) {
                connected = false
                failWaiting()
                macName = null
                link = null
                scheduleRetry()
                onDisconnected?.invoke()
            }
        }
        conn.onPacket = onPacket@{ packet ->
            if (conn !== link) return@onPacket
            packet.hello?.let { macName = it }
            packet.face?.let { onFace?.invoke(it) }
            if (packet.command == null) return@onPacket
            val reply = packet.callID?.let { waiting.remove(it) }
            if (reply != null) reply(packet) else onCommand?.invoke(packet)
        }
        link = conn
        conn.start()
    }

    private fun failWaiting() {
        val pending = waiting.values.toList()
        waiting.clear()
        pending.forEach { it(null) }
    }

    fun send(packet: Packet) {
        link?.send(packet)
    }

    /** Sends a request to the Mac and calls back with its reply (null if the Mac went away). */
    fun request(packet: Packet, done: (Packet?) -> Unit) {
        val link = link
        if (link == null || !connected) {
            done(null)
            return
        }
        val id = UUID.randomUUID().toString().uppercase()
        waiting[id] = done
        link.send(packet.copy(callID = id))
    }

    private val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    companion object {
        /**
         * Older Android versions hand back Bonjour names with DNS-SD escapes, so "Riley’s MacBook" arrives as
         * "Riley\226\128\153s\032MacBook". This turns them back into the name the Mac shows.
         */
        fun unescape(name: String): String {
            if ('\\' !in name) return name
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < name.length) {
                val c = name[i]
                if (c == '\\' && i + 3 < name.length && name.substring(i + 1, i + 4).all(Char::isDigit)) {
                    bytes.write(name.substring(i + 1, i + 4).toInt())
                    i += 4
                } else if (c == '\\' && i + 1 < name.length) {
                    bytes.write(name[i + 1].toString().toByteArray(Charsets.UTF_8))
                    i += 2
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i += 1
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
