package com.example.runtime.layer

import com.example.di.LocalhostApp
import com.example.runtime.RuntimeWarehouse
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONObject

class PlatformGraph(private val warehouse: RuntimeWarehouse) {
    val runtimeGraph = RuntimeGraph(warehouse)
    val dependencyGraph = DependencyGraph(warehouse)
    val applicationGraph = ApplicationGraph(warehouse)
    val packGraph = PackGraph(warehouse)
}

class RuntimeGraph(private val warehouse: RuntimeWarehouse) {
    suspend fun getRuntimeConsumers(runtimeType: String): List<String> {
        val servers = LocalhostApp.db.serverDao().getAllServers().firstOrNull() ?: emptyList()
        return servers.filter { it.runtimeType == runtimeType }.map { it.id }
    }
    
    suspend fun safeDeleteRuntime(runtimeType: String): Boolean {
        val consumers = getRuntimeConsumers(runtimeType)
        if (consumers.isEmpty()) {
            warehouse.getRuntimeDir(runtimeType).deleteRecursively()
            val registry = try { JSONObject(warehouse.registryFile.readText()) } catch (e: Exception) { JSONObject() }
            registry.remove(runtimeType)
            warehouse.registryFile.writeText(registry.toString(2))
            return true
        }
        return false
    }
}

class DependencyGraph(private val warehouse: RuntimeWarehouse) {
    fun getDependencyConsumers(runtimeType: String, packageName: String): List<String> {
        val registry = getDependencyRegistry()
        val list = mutableListOf<String>()
        val typeRegistry = registry.optJSONObject(runtimeType) ?: return emptyList()
        val entry = typeRegistry.optJSONObject(packageName) ?: return emptyList()
        val apps = entry.optJSONArray("apps") ?: return emptyList()
        for (i in 0 until apps.length()) {
            list.add(apps.getString(i))
        }
        return list.distinct()
    }
    
    fun safeDeleteDependency(runtimeType: String, packageName: String): Boolean {
        val consumers = getDependencyConsumers(runtimeType, packageName)
        if (consumers.isEmpty()) {
            val registry = getDependencyRegistry()
            val typeRegistry = registry.optJSONObject(runtimeType) ?: return true
            typeRegistry.remove(packageName)
            if (typeRegistry.length() == 0) {
                registry.remove(runtimeType)
            }
            warehouse.dependencyRegistryFile.writeText(registry.toString(2))
            return true
        }
        return false
    }
    
    private fun getDependencyRegistry() = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
}

class ApplicationGraph(private val warehouse: RuntimeWarehouse) {
    suspend fun getApplicationDependencies(appId: String): List<String> {
        val registry = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val deps = mutableListOf<String>()
        registry.keys().forEach { type ->
            val typeReg = registry.getJSONObject(type)
            typeReg.keys().forEach { pkg ->
                val arr = typeReg.getJSONObject(pkg).optJSONArray("apps")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        if (arr.getString(i) == appId) deps.add(pkg)
                    }
                }
            }
        }
        return deps
    }
    
    suspend fun getApplicationRuntime(appId: String): String? {
        val server = LocalhostApp.db.serverDao().getServerById(appId)
        return server?.runtimeType
    }
}

class PackGraph(private val warehouse: RuntimeWarehouse) {
    fun getPackConsumers(packType: String): List<String> {
        val registry = try { JSONObject(warehouse.registryFile.readText()) } catch (e: Exception) { JSONObject() }
        return if (registry.has(packType)) listOf("internal_runtime_registry") else emptyList()
    }
}
