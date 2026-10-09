package com.example.dropin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import com.example.dropin.MainActivity
import com.example.dropin.model.SharedFile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

class FileTransferService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    


    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles = _sharedFiles.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl = _serverUrl.asStateFlow()

    private val _sessionToken = MutableStateFlow<String?>(null)
    val sessionToken = _sessionToken.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadOrGenerateToken()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTransferSession()
            return START_NOT_STICKY
        }
        // Ensure foreground notification is shown promptly to satisfy Android requirements
        if (!_isRunning.value) {
            startForegroundServiceNotification("Initializing secure sharing...")
        }
        return START_NOT_STICKY
    }

    private var webRtcManager: com.example.dropin.webrtc.WebRtcManager? = null

    private fun generateNewToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Clear characters without ambiguous 0/O/1/I
        val token = (1..6).map { chars.random() }.joinToString("")
        val prefs = getSharedPreferences("dropin_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("session_token", token).apply()
        _sessionToken.value = token
        return token
    }

    private fun loadOrGenerateToken() {
        val prefs = getSharedPreferences("dropin_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("session_token", null) ?: generateNewToken()
        _sessionToken.value = token
    }

    fun startTransferSession(files: List<SharedFile>) {
        _sharedFiles.value = files
        val token = generateNewToken()
        _sessionToken.value = token
        
        acquireLocks()
        
        try {
            webRtcManager?.stop()
            webRtcManager = com.example.dropin.webrtc.WebRtcManager(applicationContext, token)
            webRtcManager?.sharedFiles = files
            webRtcManager?.listener = object : com.example.dropin.webrtc.WebRtcManager.WebRtcListener {
                override fun onConnected() {
                    updateNotification("WebRTC Connected and Streaming")
                }
                override fun onDisconnected() {
                    updateNotification("WebRTC Disconnected")
                }
                override fun onError(error: String) {
                    updateNotification("WebRTC Error: $error")
                }
            }
            webRtcManager?.start()
            
            _isRunning.value = true
            startForegroundServiceNotification("Waiting for Secure P2P Connection...")
            
            val displayUrl = "https://dropin-web-kappa.vercel.app?pin=$token"
            _serverUrl.value = displayUrl
            
        } catch (e: Exception) {
            stopTransferSession()
        }
    }

    fun stopTransferSession() {
        releaseLocks()
        try {
            webRtcManager?.stop()
        } catch (e: Exception) { }
        webRtcManager = null
        _isRunning.value = false
        _serverUrl.value = null
        _sharedFiles.value = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dropin::TransferWakeLock")
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Dropin::TransferWifiLock")
        }
        wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max
        wifiLock?.acquire()
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) { }
    }

    private fun startForegroundServiceNotification(content: String) {
        val notification = createNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dropin File Stream")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Dropin file stream notifications"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopTransferSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "file_transfer_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.dropin.action.STOP"
    }
}
