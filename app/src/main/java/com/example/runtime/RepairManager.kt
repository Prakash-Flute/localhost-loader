package com.example.runtime

import java.io.File
import org.json.JSONObject

import com.example.runtime.layer.PlatformGraph
import com.example.runtime.consistency.StateAuthorityManager

class RepairManager(private val warehouse: RuntimeWarehouse, private val packManager: RuntimePackManager) {

    suspend fun performFullPlatformRepair(progressCallback: (Float, String) -> Unit) {
        val authorityManager = StateAuthorityManager(warehouse)
        progressCallback(0.1f, "Repairing Warehouse...")
        repairWarehouse()
        
        progressCallback(0.3f, "Reconciling Platform Authority State...")
        authorityManager.reconcileState()
        
        progressCallback(0.5f, "Repairing Registries...")
        repairRegistries()

        progressCallback(0.7f, "Repairing Dependencies...")
        repairDependencies()
        
        progressCallback(0.9f, "Repairing Graph Integrity...")
        repairGraphIntegrity()

        progressCallback(1.0f, "Platform Repair Complete.")
    }

    private suspend fun repairGraphIntegrity() {
        val platformGraph = PlatformGraph(warehouse)
        
        // Remove orphaned runtimes using graph rules
        val registry = try { JSONObject(warehouse.registryFile.readText()) } catch (e: Exception) { JSONObject() }
        val iterRun = registry.keys()
        val orphanedRuns = mutableListOf<String>()
        while (iterRun.hasNext()) {
            val key = iterRun.next()
            if (platformGraph.runtimeGraph.getRuntimeConsumers(key).isEmpty()) {
                orphanedRuns.add(key)
            }
        }
        orphanedRuns.forEach { platformGraph.runtimeGraph.safeDeleteRuntime(it) }

        // Remove orphaned dependencies
        val depRegistry = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val orphanedDeps = mutableListOf<Pair<String, String>>()
        depRegistry.keys().forEach { type ->
            val typeReg = depRegistry.getJSONObject(type)
            typeReg.keys().forEach { pkg ->
                if (platformGraph.dependencyGraph.getDependencyConsumers(type, pkg).isEmpty()) {
                    orphanedDeps.add(type to pkg)
                }
            }
        }
        orphanedDeps.forEach { platformGraph.dependencyGraph.safeDeleteDependency(it.first, it.second) }
    }

    private fun repairWarehouse() {
        if (!warehouse.baseDir.exists()) warehouse.baseDir.mkdirs()
        if (!warehouse.runtimesDir.exists()) warehouse.runtimesDir.mkdirs()
        if (!warehouse.cacheDir.exists()) warehouse.cacheDir.mkdirs()
        if (!warehouse.appsDir.exists()) warehouse.appsDir.mkdirs()
        warehouse.cleanTempFiles()
    }

    private fun repairRegistries() {
        if (!warehouse.registryFile.exists() || !isValidJSON(warehouse.registryFile)) {
            warehouse.registryFile.writeText("{}")
        }
        if (!warehouse.dependencyRegistryFile.exists() || !isValidJSON(warehouse.dependencyRegistryFile)) {
            warehouse.dependencyRegistryFile.writeText("{}")
        }
        if (!warehouse.processJournalFile.exists() || !isValidJSON(warehouse.processJournalFile)) {
            warehouse.processJournalFile.writeText("[]")
        }
        packManager.cleanupPacks()
    }

    private fun repairDependencies() {
        if (!warehouse.dependencyRegistryFile.exists()) return
        val registry = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { return }
        
        val appsDirList = warehouse.appsDir.listFiles()?.map { it.name } ?: emptyList()
        val toRemoveTypes = mutableListOf<String>()

        registry.keys().forEach { type ->
            val typeReg = registry.getJSONObject(type)
            val toRemovePkgs = mutableListOf<String>()
            typeReg.keys().forEach { pkg ->
                val entry = typeReg.optJSONObject(pkg)
                if (entry != null) {
                    val appsArray = entry.optJSONArray("apps")
                    if (appsArray != null) {
                        var needsUpdate = false
                        val validApps = org.json.JSONArray()
                        for (i in 0 until appsArray.length()) {
                            val appId = appsArray.getString(i)
                            if (appsDirList.contains(appId)) validApps.put(appId)
                            else needsUpdate = true
                        }
                        if (validApps.length() == 0) toRemovePkgs.add(pkg)
                        else if (needsUpdate) entry.put("apps", validApps)
                    } else toRemovePkgs.add(pkg)
                }
            }
            toRemovePkgs.forEach { typeReg.remove(it) }
            if (typeReg.length() == 0) toRemoveTypes.add(type)
        }
        
        toRemoveTypes.forEach { registry.remove(it) }
        warehouse.dependencyRegistryFile.writeText(registry.toString(2))
    }

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
