package com.example.runtime.recovery

import com.example.di.LocalhostApp
import com.example.runtime.RuntimeWarehouse
import com.example.runtime.consistency.StateAuthorityManager
import com.example.runtime.layer.PlatformGraph
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import org.json.JSONObject

class PlatformRecoveryEngine(private val warehouse: RuntimeWarehouse, private val authorityManager: StateAuthorityManager) {

    suspend fun performRecovery() {
        // Reconstruct basic corrupted structure
        recoverInterruptedInstalls()
        recoverCorruptedRegistries()

        // Wipe volatile knowledge if broken, graphs will regenerate
        reconstructPlatformKnowledge()
        
        // Final state authority reconciliation
        authorityManager.reconcileState()
    }

    private suspend fun reconstructPlatformKnowledge() {
        val platformGraph = PlatformGraph(warehouse)
        val apps = LocalhostApp.db.serverDao().getAllServers().firstOrNull() ?: emptyList()
        val registry = readJson(warehouse.registryFile)
        val depRegistry = readJson(warehouse.dependencyRegistryFile)
        val packRegistryFile = File(warehouse.baseDir, "pack_registry.json")
        val packRegistry = readJson(packRegistryFile)

        // 1. Rebuild application & dependency & runtime graphs from metadata
        apps.forEach { app ->
            // Rebuild Runtime Graph Entry
            if (!registry.has(app.runtimeType) && app.runtimeType != "static") {
                val rDir = warehouse.getRuntimeDir(app.runtimeType)
                if (rDir.exists() && rDir.isDirectory) {
                    val rObj = JSONObject().apply {
                        put("version", app.runtimeVersion)
                        put("size", rDir.walkTopDown().sumOf { it.length() })
                    }
                    registry.put(app.runtimeType, rObj)
                    
                    // Rebuild Pack Graph Entry
                    val pObj = JSONObject().apply {
                        put("version", app.runtimeVersion)
                        put("integrityState", "OK")
                    }
                    packRegistry.put(app.runtimeType, pObj)
                } else {
                    LocalhostApp.db.serverDao().updateServerStatus(app.id, "ERROR", null)
                }
            }

            // Rebuild Dependency Graph Entries from loader.json
            val loaderFile = File(app.codePath, "loader.json")
            if (loaderFile.exists()) {
                try {
                    val loaderJson = JSONObject(loaderFile.readText())
                    val deps = loaderJson.optJSONArray("dependencies")
                    if (deps != null) {
                        for (i in 0 until deps.length()) {
                            val depName = deps.getString(i)
                            val typeReg = depRegistry.optJSONObject(app.runtimeType) ?: JSONObject().also { depRegistry.put(app.runtimeType, it) }
                            val entry = typeReg.optJSONObject(depName) ?: JSONObject().apply {
                                put("version", "unknown")
                                put("apps", org.json.JSONArray())
                            }
                            val appsArr = entry.optJSONArray("apps") ?: org.json.JSONArray()
                            var foundApp = false
                            for (j in 0 until appsArr.length()) {
                                if (appsArr.getString(j) == app.id) foundApp = true
                            }
                            if (!foundApp) appsArr.put(app.id)
                            entry.put("apps", appsArr)
                            typeReg.put(depName, entry)
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        // Save reconstructed state
        warehouse.registryFile.writeText(registry.toString(2))
        warehouse.dependencyRegistryFile.writeText(depRegistry.toString(2))
        packRegistryFile.writeText(packRegistry.toString(2))

        // Clean orphaned packages using graph rules
        val orphanedDeps = mutableListOf<Pair<String,String>>()
        depRegistry.keys().forEach { type ->
            val typeReg = depRegistry.getJSONObject(type)
            typeReg.keys().forEach { pkg ->
                if (platformGraph.dependencyGraph.getDependencyConsumers(type, pkg).isEmpty()) {
                    orphanedDeps.add(type to pkg)
                }
            }
        }
        orphanedDeps.forEach { platformGraph.dependencyGraph.safeDeleteDependency(it.first, it.second) }
        
        // Clean orphaned runtimes
        val orphanedRuns = mutableListOf<String>()
        registry.keys().forEach { key ->
            if (platformGraph.runtimeGraph.getRuntimeConsumers(key).isEmpty()) {
                orphanedRuns.add(key)
            }
        }
        orphanedRuns.forEach { platformGraph.runtimeGraph.safeDeleteRuntime(it) }
    }

    private fun recoverInterruptedInstalls() {
        // Check for temps and orphaned apps
        warehouse.appsDir.listFiles()?.forEach { appDir ->
            val codeDir = File(appDir, "code")
            val isValid = codeDir.exists() && codeDir.listFiles()?.isNotEmpty() == true
            if (!isValid) {
                appDir.deleteRecursively()
            }
        }
        
        // Clean temp files securely without using name heuristics
        warehouse.cleanTempFiles()
    }

    private fun recoverCorruptedRegistries() {
        if (!warehouse.registryFile.exists() || !isValidJSON(warehouse.registryFile)) {
            warehouse.registryFile.writeText("{}")
        }
        if (!warehouse.dependencyRegistryFile.exists() || !isValidJSON(warehouse.dependencyRegistryFile)) {
            warehouse.dependencyRegistryFile.writeText("{}")
        }
        if (!warehouse.processJournalFile.exists() || !isValidJSON(warehouse.processJournalFile)) {
            warehouse.processJournalFile.writeText("[]")
        }
        val packRegistryFile = File(warehouse.baseDir, "pack_registry.json")
        if (!packRegistryFile.exists() || !isValidJSON(packRegistryFile)) {
            packRegistryFile.writeText("{}")
        }
    }

    private fun readJson(file: File): JSONObject = try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }

    private fun isValidJSON(file: File): Boolean {
        return try {
            val txt = file.readText()
            if (txt.startsWith("[")) org.json.JSONArray(txt) else org.json.JSONObject(txt)
            true
        } catch (e: Exception) {
            false
        }
    }
}
