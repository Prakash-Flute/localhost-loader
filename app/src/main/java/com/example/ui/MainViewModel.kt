package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.ServerEntity
import com.example.di.LocalhostApp
import com.example.install.InstallManager
import com.example.install.InstallProgress
import com.example.runtime.NodeAdapter
import com.example.runtime.PythonAdapter
import com.example.runtime.StaticAdapter
import com.example.service.RuntimeService
import com.example.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.ContextCompat

import com.example.service.IRuntimeService

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val servers = LocalhostApp.db.serverDao().getAllServers().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val urlCenter = com.example.url.UrlCenter(application)
    
    private var runtimeService: IRuntimeService? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            runtimeService = IRuntimeService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runtimeService = null
        }
    }

    init {
        val intent = Intent(application, RuntimeService::class.java)
        ContextCompat.startForegroundService(application, intent)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        // Reset starting states on boot
        viewModelScope.launch {
            val warehouse = com.example.runtime.RuntimeWarehouse(application)
            val authManager = com.example.runtime.consistency.StateAuthorityManager(warehouse)
            com.example.runtime.recovery.PlatformRecoveryEngine(warehouse, authManager).performRecovery()
            LocalhostApp.db.serverDao().stopAllServers()
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(serviceConnection)
        urlCenter.cleanup()
    }

    fun startAppServer(server: ServerEntity) {
        viewModelScope.launch {
            LocalhostApp.db.serverDao().updateServerStatus(server.id, "STARTING", null)
            val port = withContext(Dispatchers.IO) { NetworkUtils.findFreePort(8000) }
            
            val adapter = when(server.runtimeType) {
                "python" -> PythonAdapter()
                "node" -> NodeAdapter()
                else -> StaticAdapter()
            }
            
            val buildCommand = adapter.buildStartCommand(
                com.example.runtime.Environment(server.envPath, emptyMap()),
                server.entryPoint,
                port,
                emptyMap()
            )
            val logFile = File(server.codePath).parentFile?.let { File(it, "logs/server.log") }
            logFile?.parentFile?.mkdirs()
            
            try {
                runtimeService?.startServer(server.id, buildCommand, mapOf("PORT" to port.toString()), port, logFile?.absolutePath ?: "/dev/null", server.codePath)
                LocalhostApp.db.serverDao().updateServerStatus(server.id, "RUNNING", port)
                urlCenter.registerServer(server.id, port)
                urlCenter.trackLaunch(server.id)
            } catch (e: Exception) {
                LocalhostApp.db.serverDao().updateServerStatus(server.id, "CRASHED", null)
                urlCenter.unregisterServer(server.id)
            }
        }
    }

    fun stopAppServer(server: ServerEntity) {
        viewModelScope.launch {
            runtimeService?.stopServer(server.id)
            LocalhostApp.db.serverDao().updateServerStatus(server.id, "STOPPED", null)
            urlCenter.unregisterServer(server.id)
        }
    }

    fun deleteAppServer(server: ServerEntity) {
        viewModelScope.launch {
            runtimeService?.stopServer(server.id)
            withContext(Dispatchers.IO) {
                val appDir = File(LocalhostApp.warehousePath, "apps/${server.id}")
                appDir.deleteRecursively()
                LocalhostApp.db.serverDao().deleteServer(server)
            }
        }
    }

    fun getLogs(server: ServerEntity): String {
        val logFile = File(server.codePath).parentFile?.let { File(it, "logs/server.log") } ?: return ""
        return runtimeService?.getLogs(logFile.absolutePath, 150) ?: "Service not connected"
    }

    fun installFromUri(uri: Uri, isZip: Boolean, name: String): Flow<InstallProgress> {
        val ctx = getApplication<Application>()
        return if (isZip) {
            InstallManager.installFromZip(ctx, uri, name)
        } else {
            InstallManager.installFromFolder(ctx, uri, name)
        }
    }

    fun installFromUrl(url: String, name: String): Flow<InstallProgress> {
        val ctx = getApplication<Application>()
        return InstallManager.installFromUrl(ctx, url, name)
    }
}
