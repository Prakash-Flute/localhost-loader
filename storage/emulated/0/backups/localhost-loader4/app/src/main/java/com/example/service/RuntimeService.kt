package com.example.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import androidx.core.content.getSystemService
import com.example.runtime.RuntimeWarehouse
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MonitoredServer(
    val appId: String,
    val port: Int,
    val command: List<String>,
    val envVars: Map<String, String>,
    val logFile: File,
    val codePath: String,
    var failures: Int = 0,
    var process: Process? = null,
    var monitorJob: Job? = null
)

class RuntimeService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeServers = java.util.concurrent.ConcurrentHashMap<String, MonitoredServer>()
    private val staticServers = java.util.concurrent.ConcurrentHashMap<String, StaticServer>()
    private lateinit var warehouse: RuntimeWarehouse
    private val journalMutex = Mutex()
    private val procJournalsFile by lazy { File(warehouse.baseDir, "journal_process.json") }
    private val execJournalsFile by lazy { File(warehouse.baseDir, "journal_execution.json") }
    private val recJournalsFile by lazy { File(warehouse.baseDir, "journal_recovery.json") }
    private val stateJournalsFile by lazy { File(warehouse.baseDir, "journal_state.json") }
    private val runJournalsFile by lazy { File(warehouse.baseDir, "journal_runtime.json") }
    
    private val binder = object : IRuntimeService.Stub() {
        override fun startServer(appId: String, command: List<String>?, envVars: Map<*, *>?, port: Int, logFilePath: String?, codePath: String?): Int {
            if (command == null || envVars == null || logFilePath == null || codePath == null) return -1
            val env = envVars.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
            return this@RuntimeService.startServer(appId, command, env, port, File(logFilePath), codePath)
        }
        override fun stopServer(appId: String?) { if (appId != null) this@RuntimeService.stopServer(appId) }
        override fun stopAll() { this@RuntimeService.stopAll() }
        override fun getLogs(logFilePath: String?, lines: Int): String {
            if (logFilePath == null) return ""
            return this@RuntimeService.getLogs(File(logFilePath), lines)
        }
    }

    override fun onCreate() {
        super.onCreate()
        warehouse = RuntimeWarehouse(this)
        listOf(procJournalsFile, execJournalsFile, recJournalsFile, stateJournalsFile, runJournalsFile).forEach {
            if (!it.exists()) it.writeText("[]")
        }
        scope.launch { recoverJournals() }
    }

    private suspend fun writeJournal(file: File, action: String, appId: String, data: JSONObject) = journalMutex.withLock {
        val arr = try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        val entry = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("action", action)
            put("appId", appId)
            put("data", data)
        }
        arr.put(entry)
        file.writeText(arr.toString(2))
    }

    private suspend fun recoverJournals() = journalMutex.withLock {
        val procArr = try { JSONArray(procJournalsFile.readText()) } catch (e: Exception) { JSONArray() }
        val latestStates = mutableMapOf<String, JSONObject>()
        for(i in 0 until procArr.length()) {
            val entry = procArr.getJSONObject(i)
            val appId = entry.getString("appId")
            latestStates[appId] = entry
        }
        
        latestStates.forEach { (appId, entry) ->
            if (entry.getString("action") == "START") {
                val data = entry.getJSONObject("data")
                val port = data.getInt("port")
                val cmdArray = data.getJSONArray("command")
                val command = List(cmdArray.length()) { i -> cmdArray.getString(i) }
                val envObj = data.getJSONObject("envVars")
                val envVars = mutableMapOf<String, String>()
                envObj.keys().forEach { k -> envVars[k] = envObj.getString(k) }
                val logFile = File(data.getString("logFile"))
                val codePath = data.optString("codePath", "")
                
                if (!healthCheckPort(port)) {
                    scope.launch { startServer(appId, command, envVars, port, logFile, codePath) }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_ALL") stopAll()
        startForeground(1, createNotification())
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val count = activeServers.size + staticServers.size
        val stopIntent = Intent(this, RuntimeService::class.java).apply { action = "STOP_ALL" }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val channelId = "localhost_loader_service"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Server Status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Localhost Platform Hosting")
            .setContentText("$count servers running securely")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop All", stopPending)
            .build()
    }

    private fun updateNotification() { getSystemService(NotificationManager::class.java)?.notify(1, createNotification()) }

    fun startServer(appId: String, command: List<String>, envVars: Map<String, String>, port: Int, logFile: File, codePath: String): Int {
        scope.launch {
            val data = JSONObject().apply {
                put("port", port)
                put("command", JSONArray(command))
                put("envVars", JSONObject(envVars))
                put("logFile", logFile.absolutePath)
                put("codePath", codePath)
            }
            writeJournal(procJournalsFile, "START", appId, data)
            writeJournal(execJournalsFile, "EXECUTE", appId, data)
            writeJournal(stateJournalsFile, "RUNNING", appId, data)
        }

        if (command.isNotEmpty() && command.first() == "internal:static") {
            val root = File(command[1])
            val server = StaticServer(port, root)
            server.start()
            staticServers[appId] = server
            updateNotification()
            return port
        } else {
            val monitored = MonitoredServer(appId, port, command, envVars, logFile, codePath)
            activeServers[appId] = monitored
            launchProcessWithMonitor(monitored)
            updateNotification()
            return port
        }
    }

    private fun launchProcessWithMonitor(server: MonitoredServer) {
        val pb = ProcessBuilder(server.command)
        pb.environment().putAll(server.envVars)
        pb.environment()["PORT"] = server.port.toString()
        if (server.codePath.isNotBlank()) pb.directory(File(server.codePath))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(server.logFile))
        
        try {
            server.process = pb.start()
            scope.launch { writeJournal(runJournalsFile, "PROCESS_SPAWNED", server.appId, JSONObject().put("pid", -1)) }
            
            server.monitorJob = scope.launch {
                while (isActive) {
                    delay(5000)
                    val isAlive = server.process?.isAlive == true
                    if (!isAlive) {
                        server.failures++
                        scope.launch { writeJournal(recJournalsFile, "RECOVERY_ATTEMPT", server.appId, JSONObject().put("failures", server.failures)) }
                        if (server.failures > 5) break
                        delay(Math.min(60000L, 2000L * Math.pow(2.0, server.failures.toDouble()).toLong()))
                        server.process = pb.start()
                    } else {
                        if (healthCheckPort(server.port)) server.failures = 0
                    }
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to start process: ${e.message}")
        }
    }

    private fun healthCheckPort(port: Int): Boolean {
        return try { Socket("127.0.0.1", port).use { true } } catch (e: Exception) { false }
    }

    fun stopServer(appId: String) {
        activeServers.remove(appId)?.let {
            it.monitorJob?.cancel()
            it.process?.destroy()
        }
        staticServers.remove(appId)?.stop()
        
        scope.launch {
            writeJournal(procJournalsFile, "STOP", appId, JSONObject())
            writeJournal(stateJournalsFile, "STOPPED", appId, JSONObject())
        }
        updateNotification()
    }

    fun stopAll() {
        activeServers.keys().toList().forEach { stopServer(it) }
        staticServers.keys().toList().forEach { stopServer(it) }
    }

    fun getLogs(logFile: File, lines: Int): String {
        if (!logFile.exists()) return "No logs found."
        try {
            val raf = RandomAccessFile(logFile, "r")
            val length = raf.length()
            if (length == 0L) return "Empty log."
            var linesCount = 0
            var ptr = length - 1
            while (ptr >= 0 && linesCount < lines) {
                raf.seek(ptr)
                if (raf.read() == '\n'.code && ptr != length - 1) linesCount++
                ptr--
            }
            if (ptr < 0) raf.seek(0)
            val buffer = ByteArray((length - raf.filePointer).toInt())
            raf.read(buffer)
            return String(buffer)
        } catch (e: Exception) {
            return "Error reading logs: ${e.message}"
        }
    }

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }
}
