package com.example.runtime

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PackMetadataStore(private val packRegistryFile: File) {
    fun getRegistry(): JSONObject = try { JSONObject(packRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
    fun saveRegistry(json: JSONObject) = packRegistryFile.writeText(json.toString(2))
}

class PackRelationshipStore(private val store: PackMetadataStore) {
    fun registerPackRelationship(type: String, version: String, deps: List<String>, hash: String) {
        val json = store.getRegistry()
        val packData = JSONObject().apply {
            put("version", version)
            put("dependencies", JSONArray(deps))
            put("hash", hash)
            put("lastVerified", System.currentTimeMillis())
            put("integrityState", "VALID")
            put("relationshipState", "ACTIVE")
            put("versionState", "LATEST")
        }
        json.put(type, packData)
        store.saveRegistry(json)
    }
}

class PackValidationEngine(private val warehouse: RuntimeWarehouse, private val store: PackMetadataStore) {
    fun verifyPackIntegrity(type: String): Boolean {
        val dir = warehouse.getRuntimeDir(type)
        if (!dir.exists()) return false
        val json = store.getRegistry()
        val packData = json.optJSONObject(type) ?: return false
        val expectedHash = packData.optString("hash", "")
        if (expectedHash.isEmpty()) return true
        val isOk = expectedHash == generateDirectoryHash(dir)
        if (!isOk) {
            packData.put("integrityState", "CORRUPTED")
            store.saveRegistry(json)
        }
        return isOk
    }
    
    fun generateDirectoryHash(dir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.forEach { file ->
            md.update(file.name.toByteArray())
            md.update(file.length().toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class PackCatalog(private val warehouse: RuntimeWarehouse, private val store: PackMetadataStore) {
    fun getInstalledPacks(): List<String> {
        val json = store.getRegistry()
        val installed = mutableListOf<String>()
        val iter = json.keys()
        while (iter.hasNext()) installed.add(iter.next())
        return installed
    }
}

class PackRepairEngine(private val warehouse: RuntimeWarehouse) {
    fun prepareForRepair(type: String) {
        warehouse.getRuntimeDir(type).deleteRecursively()
    }
}

class PackMigrationEngine {
    fun computeMigration(fromVersion: String, toVersion: String): Boolean = true
}

class RuntimePackManager(private val warehouse: RuntimeWarehouse) {
    private val adapters = listOf(NodeAdapter(), PythonAdapter(), PhpAdapter(), StaticAdapter())
    private val packRegistryFile = File(warehouse.baseDir, "pack_registry.json")
    val metadataStore = PackMetadataStore(packRegistryFile)
    val relationshipsStore = PackRelationshipStore(metadataStore)
    val validationEngine = PackValidationEngine(warehouse, metadataStore)
    val catalog = PackCatalog(warehouse, metadataStore)
    val repair = PackRepairEngine(warehouse)
    val migration = PackMigrationEngine()

    init {
        if (!packRegistryFile.exists()) packRegistryFile.writeText("{}")
    }

    fun getAdapter(type: String): RuntimeAdapter? = adapters.find { it.runtimeType == type }

    fun installRuntimeIfNeeded(type: String, downloadUrl: String, progressCallback: (Float, String) -> Unit): Result<Unit> {
        val adapter = getAdapter(type) ?: return Result.failure(IllegalArgumentException("Unsupported runtime: $type"))
        
        if (warehouse.isRuntimeInstalled(type)) {
            if (!validationEngine.verifyPackIntegrity(type)) {
                progressCallback(0.1f, "Pack integrity failed. Repairing...")
                repair.prepareForRepair(type)
            } else {
                progressCallback(1.0f, "Runtime already verified in registry.")
                return Result.success(Unit)
            }
        }

        val dir = warehouse.getRuntimeDir(type)
        if (adapter.isAvailable(dir)) {
            progressCallback(1.0f, "Runtime found but not registered. Registering...")
            val version = adapter.getInstalledVersion(dir) ?: "1.0.0"
            warehouse.registerRuntime(type, version, dir.walkTopDown().sumOf { it.length() })
            relationshipsStore.registerPackRelationship(type, version, emptyList(), validationEngine.generateDirectoryHash(dir))
            return Result.success(Unit)
        }
        
        val tempFile = warehouse.createTempFile("pack", ".zip")
        val result = adapter.installRuntime(dir, tempFile, downloadUrl, progressCallback)
        if (result.isSuccess) {
            val version = adapter.getInstalledVersion(dir) ?: "1.0.0"
            warehouse.registerRuntime(type, version, dir.walkTopDown().sumOf { it.length() })
            relationshipsStore.registerPackRelationship(type, version, emptyList(), validationEngine.generateDirectoryHash(dir))
        }
        return result
    }

    fun cleanupPacks() {
        if (!packRegistryFile.exists()) return
        val installed = catalog.getInstalledPacks()
        warehouse.getRuntimeDir("").listFiles()?.forEach { file ->
            if (file.isDirectory && !installed.contains(file.name)) {
                file.deleteRecursively()
            }
        }
    }
}
