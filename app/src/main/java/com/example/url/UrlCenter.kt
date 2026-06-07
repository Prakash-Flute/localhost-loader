package com.example.url

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import com.example.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

data class ServerUrlInfo(
    val appId: String,
    val port: Int,
    val localUrl: String,
    val lanUrl: String,
    val qrGeneratedCount: Int = 0,
    val lastLaunchedAt: Long = 0L,
    val networkState: String = "UNKNOWN",
    val validityState: String = "ACTIVE"
)

class UrlCenter(private val context: Context) {
    private val _registeredServers = MutableStateFlow<Map<String, ServerUrlInfo>>(emptyMap())
    val registeredServers: StateFlow<Map<String, ServerUrlInfo>> = _registeredServers.asStateFlow()
    private val historyFile = File(context.filesDir, "url_history.json")
    
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUrls()
        }
    }
    
    init {
        loadHistory()
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(receiver, filter)
    }
    
    fun cleanup() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }

    private fun loadHistory() {
        if (!historyFile.exists()) historyFile.writeText("{}")
        val root = try { JSONObject(historyFile.readText()) } catch(e: Exception) { JSONObject() }
        val current = mutableMapOf<String, ServerUrlInfo>()
        root.keys().forEach { id ->
            val obj = root.getJSONObject(id)
            current[id] = ServerUrlInfo(
                appId = id,
                port = obj.optInt("port", 0),
                localUrl = obj.optString("localUrl", ""),
                lanUrl = obj.optString("lanUrl", ""),
                qrGeneratedCount = obj.optInt("qrGeneratedCount", 0),
                lastLaunchedAt = obj.optLong("lastLaunchedAt", 0L),
                networkState = obj.optString("networkState", "UNKNOWN"),
                validityState = obj.optString("validityState", "INACTIVE")
            )
        }
        _registeredServers.value = current
    }
    
    private fun saveHistory() {
        val obj = JSONObject()
        _registeredServers.value.forEach { (id, info) ->
            obj.put(id, JSONObject().apply {
                put("port", info.port)
                put("localUrl", info.localUrl)
                put("lanUrl", info.lanUrl)
                put("qrGeneratedCount", info.qrGeneratedCount)
                put("lastLaunchedAt", info.lastLaunchedAt)
                put("validityState", info.validityState)
                put("networkState", info.networkState)
            })
        }
        historyFile.writeText(obj.toString(2))
    }

    fun registerServer(appId: String, port: Int) {
        val lanIp = NetworkUtils.getLanIpAddress(context)
        val hist = if (historyFile.exists()) JSONObject(historyFile.readText()).optJSONObject(appId) else null
        
        val netState = if (lanIp == "127.0.0.1") "DISCONNECTED" else "CONNECTED"
        
        val info = ServerUrlInfo(
            appId = appId,
            port = port,
            localUrl = "http://127.0.0.1:$port",
            lanUrl = "http://$lanIp:$port",
            qrGeneratedCount = hist?.optInt("qrGeneratedCount", 0) ?: 0,
            lastLaunchedAt = System.currentTimeMillis(),
            networkState = netState,
            validityState = "ACTIVE"
        )
        _registeredServers.update { current ->
            val next = current.toMutableMap()
            next[appId] = info
            next
        }
        saveHistory()
    }

    fun unregisterServer(appId: String) {
        val info = _registeredServers.value[appId] ?: return
        _registeredServers.update { current ->
            val next = current.toMutableMap()
            next[appId] = info.copy(validityState = "INACTIVE")
            next
        }
        saveHistory()
    }
    
    fun getUrlInfo(appId: String): ServerUrlInfo? = _registeredServers.value[appId]
    
    fun trackQrGeneration(appId: String) {
        val info = _registeredServers.value[appId] ?: return
        _registeredServers.update { current ->
            val next = current.toMutableMap()
            next[appId] = info.copy(qrGeneratedCount = info.qrGeneratedCount + 1)
            next
        }
        saveHistory()
    }
    
    fun trackLaunch(appId: String) {
        val info = _registeredServers.value[appId] ?: return
        _registeredServers.update { current ->
            val next = current.toMutableMap()
            next[appId] = info.copy(lastLaunchedAt = System.currentTimeMillis())
            next
        }
        saveHistory()
    }

    fun refreshUrls() {
        val lanIp = NetworkUtils.getLanIpAddress(context)
        val netState = if (lanIp == "127.0.0.1") "DISCONNECTED" else "CONNECTED"
        _registeredServers.update { current ->
            val next = current.toMutableMap()
            next.forEach { (id, info) ->
                if (info.validityState == "ACTIVE") {
                    next[id] = info.copy(lanUrl = "http://$lanIp:${info.port}", networkState = netState)
                }
            }
            next
        }
        saveHistory()
    }
}
