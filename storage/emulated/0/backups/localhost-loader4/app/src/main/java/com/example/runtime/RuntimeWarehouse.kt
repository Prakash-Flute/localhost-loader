package com.example.runtime

import java.io.File
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

data class RuntimeMetadata(
    val type: String,
    val version: String,
    val installedAt: Long,
    val sizeBytes: Long
)

class RuntimeWarehouse(private val context: Context) {
    val baseDir: File = File(context.filesDir, "warehouse")
    val runtimesDir: File = File(baseDir, "runtimes")
    val cacheDir: File = File(baseDir, "cache")
    val appsDir: File = File(baseDir, "apps")
    val tempDir: File = File(baseDir, "temp")
    val registryFile: File = File(baseDir, "registry.json")
    val dependencyRegistryFile: File = File(baseDir, "dependency_registry.json")
    val processJournalFile: File = File(baseDir, "process_journal.json")

    init {
        runtimesDir.mkdirs()
        cacheDir.mkdirs()
        appsDir.mkdirs()
        tempDir.mkdirs()
        if (!registryFile.exists()) registryFile.writeText("{}")
        if (!dependencyRegistryFile.exists()) dependencyRegistryFile.writeText("{}")
        if (!processJournalFile.exists()) processJournalFile.writeText("[]")
    }

    fun cleanTempFiles() {
        tempDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun createTempFile(prefix: String, suffix: String): File {
        return File.createTempFile(prefix, suffix, tempDir)
    }

    fun getRuntimeDir(runtimeType: String): File {
        val dir = File(runtimesDir, runtimeType)
        dir.mkdirs()
        return dir
    }

    @Synchronized
    fun registerRuntime(type: String, version: String, sizeBytes: Long) {
        val registry = readRegistry()
        val meta = RuntimeMetadata(type, version, System.currentTimeMillis(), sizeBytes)
        val json = JSONObject()
        json.put("type", meta.type)
        json.put("version", meta.version)
        json.put("installedAt", meta.installedAt)
        json.put("sizeBytes", meta.sizeBytes)
        
        registry.put(type, json)
        registryFile.writeText(registry.toString(2))
    }

    @Synchronized
    fun isRuntimeInstalled(type: String): Boolean {
        val registry = readRegistry()
        val hasEntry = registry.has(type)
        val dir = File(runtimesDir, type)
        val hasFiles = dir.exists() && dir.listFiles()?.isNotEmpty() == true
        
        if (hasEntry && !hasFiles) {
            registry.remove(type)
            registryFile.writeText(registry.toString(2))
            return false
        }
        return hasEntry && hasFiles
    }

    private fun readRegistry() = try { JSONObject(registryFile.readText()) } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun registerDependency(type: String, packageName: String, version: String, appId: String) {
        val registry = try { JSONObject(dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val typeRegistry = registry.optJSONObject(type) ?: JSONObject().also { registry.put(type, it) }
        val entry = typeRegistry.optJSONObject(packageName) ?: JSONObject().apply {
            put("version", version)
            put("apps", JSONArray())
        }
        val appsArray = entry.getJSONArray("apps")
        var found = false
        for (i in 0 until appsArray.length()) if (appsArray.getString(i) == appId) found = true
        if (!found) appsArray.put(appId)
        
        typeRegistry.put(packageName, entry)
        dependencyRegistryFile.writeText(registry.toString(2))
    }

    @Synchronized
    fun unregisterAppDependencies(appId: String) {
        val registry = try { JSONObject(dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val toRemoveTypes = mutableListOf<String>()
        registry.keys().forEach { type ->
            val typeRegistry = registry.getJSONObject(type)
            val toRemovePkgs = mutableListOf<String>()
            typeRegistry.keys().forEach { pkgName ->
                val entry = typeRegistry.getJSONObject(pkgName)
                val appsArray = entry.getJSONArray("apps")
                val newAppsArray = JSONArray()
                for (i in 0 until appsArray.length()) {
                    val id = appsArray.getString(i)
                    if (id != appId) newAppsArray.put(id)
                }
                if (newAppsArray.length() == 0) toRemovePkgs.add(pkgName)
                else entry.put("apps", newAppsArray)
            }
            toRemovePkgs.forEach { typeRegistry.remove(it) }
            if (typeRegistry.length() == 0) toRemoveTypes.add(type)
        }
        toRemoveTypes.forEach { registry.remove(it) }
        dependencyRegistryFile.writeText(registry.toString(2))
    }

    @Synchronized
    fun writeProcessJournal(appId: String, port: Int, pid: Long, command: List<String>, envVars: Map<String, String>, logFile: File) {
        val journal = try { JSONArray(processJournalFile.readText()) } catch (e: Exception) { JSONArray() }
        
        val newJournal = JSONArray()
        for (i in 0 until journal.length()) {
            val entry = journal.getJSONObject(i)
            if (entry.optString("appId") != appId) newJournal.put(entry)
        }
        
        val newEntry = JSONObject()
        newEntry.put("appId", appId)
        newEntry.put("port", port)
        newEntry.put("pid", pid)
        newEntry.put("command", JSONArray(command))
        val envObj = JSONObject()
        envVars.forEach { (k, v) -> envObj.put(k, v) }
        newEntry.put("envVars", envObj)
        newEntry.put("logFile", logFile.absolutePath)
        newEntry.put("timestamp", System.currentTimeMillis())
        newJournal.put(newEntry)
        
        processJournalFile.writeText(newJournal.toString(2))
    }

    @Synchronized
    fun removeProcessJournal(appId: String) {
        val journal = try { JSONArray(processJournalFile.readText()) } catch (e: Exception) { JSONArray() }
        val newJournal = JSONArray()
        for (i in 0 until journal.length()) {
            val entry = journal.getJSONObject(i)
            if (entry.optString("appId") != appId) newJournal.put(entry)
        }
        processJournalFile.writeText(newJournal.toString(2))
    }

    @Synchronized
    fun getProcessJournal(): List<JSONObject> {
        val journal = try { JSONArray(processJournalFile.readText()) } catch (e: Exception) { JSONArray() }
        val list = mutableListOf<JSONObject>()
        for (i in 0 until journal.length()) {
            list.add(journal.getJSONObject(i))
        }
        return list
    }
}
