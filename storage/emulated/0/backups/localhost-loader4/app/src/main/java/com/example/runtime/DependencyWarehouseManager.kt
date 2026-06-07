package com.example.runtime

import com.example.runtime.layer.PlatformGraph
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DependencyVersionIndex(private val warehouse: RuntimeWarehouse) {
    fun getVersions(runtimeType: String, packageName: String): List<String> {
        val registry = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val typeReg = registry.optJSONObject(runtimeType) ?: return emptyList()
        val entry = typeReg.optJSONObject(packageName) ?: return emptyList()
        return listOf(entry.optString("version", "unknown"))
    }
}

class DependencyHealthEngine(private val warehouse: RuntimeWarehouse) {
    data class BadDep(val runtimeType: String, val packageName: String)
    fun getCorruptedDependencies(): List<BadDep> {
        val registry = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val corrupted = mutableListOf<BadDep>()
        registry.keys().forEach { type ->
            val typeReg = registry.getJSONObject(type)
            typeReg.keys().forEach { pkg ->
                val entry = typeReg.getJSONObject(pkg)
                if (!entry.has("apps") || entry.optJSONArray("apps") == null) {
                    corrupted.add(BadDep(type, pkg))
                }
            }
        }
        return corrupted
    }
}

class DependencyConflictDetector(private val versionIndex: DependencyVersionIndex) {
    fun detectConflicts(runtimeType: String, packageName: String): Boolean = versionIndex.getVersions(runtimeType, packageName).size > 1
}

class DependencyIntegrityValidator(private val healthEngine: DependencyHealthEngine) {
    fun validateIntegrity(): Boolean = healthEngine.getCorruptedDependencies().isEmpty()
}

class DependencyWarehouseManager(private val warehouse: RuntimeWarehouse) {
    val platformGraph = PlatformGraph(warehouse)
    val versionIndex = DependencyVersionIndex(warehouse)
    val healthEngine = DependencyHealthEngine(warehouse)
    val conflictDetector = DependencyConflictDetector(versionIndex)
    val integrityValidator = DependencyIntegrityValidator(healthEngine)
    private val mutex = Mutex()

    suspend fun getDependencyUsage(): JSONObject = mutex.withLock {
        if (!warehouse.dependencyRegistryFile.exists()) return@withLock JSONObject()
        return@withLock try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
    }

    suspend fun getOrphanedDependencies(): List<Pair<String, String>> = mutex.withLock {
        val registry = getDependencyUsage()
        val orphaned = mutableListOf<Pair<String, String>>()
        registry.keys().forEach { type ->
            val typeReg = registry.getJSONObject(type)
            typeReg.keys().forEach { pkg ->
                if (platformGraph.dependencyGraph.getDependencyConsumers(type, pkg).isEmpty()) {
                    orphaned.add(type to pkg)
                }
            }
        }
        orphaned
    }

    suspend fun cleanupOrphanedDependencies() = mutex.withLock {
        val orphaned = getOrphanedDependencies()
        orphaned.forEach { (type, pkg) ->
            platformGraph.dependencyGraph.safeDeleteDependency(type, pkg)
        }
    }
}
