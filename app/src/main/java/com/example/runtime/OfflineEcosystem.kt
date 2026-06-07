package com.example.runtime

import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import com.example.runtime.consistency.StateAuthorityManager
import com.example.runtime.recovery.PlatformRecoveryEngine

class OfflineEcosystem(private val warehouse: RuntimeWarehouse) {

    fun backupPlatform(destZipFile: File): Result<Unit> {
        return try {
            val tempDir = File(warehouse.baseDir.parentFile, "backup_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            
            warehouse.baseDir.copyRecursively(File(tempDir, "warehouse"), true)
            
            val parent1 = warehouse.baseDir.parentFile
            val parent2 = parent1?.parentFile
            val dbFile = if (parent2 != null) File(parent2, "databases/localhost_loader.db") else null
            if(dbFile != null && dbFile.exists()) {
                val dbBackupDir = File(tempDir, "database")
                dbBackupDir.mkdirs()
                dbFile.copyTo(File(dbBackupDir, "localhost_loader.db"), true)
            }
            
            val urlHistoryFile = File(warehouse.baseDir.parentFile, "url_history.json")
            if (urlHistoryFile.exists()) {
                val settingsBackupDir = File(tempDir, "settings")
                settingsBackupDir.mkdirs()
                urlHistoryFile.copyTo(File(settingsBackupDir, "url_history.json"), true)
            }
            
            val appPrefsFile = if (parent2 != null) File(parent2, "shared_prefs/app_prefs.xml") else null
            if (appPrefsFile != null && appPrefsFile.exists()) {
                val settingsBackupDir = File(tempDir, "settings")
                settingsBackupDir.mkdirs()
                appPrefsFile.copyTo(File(settingsBackupDir, "app_prefs.xml"), true)
            }
            
            val fos = FileOutputStream(destZipFile)
            val zos = ZipOutputStream(fos)
            tempDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = file.absolutePath.substring(tempDir.absolutePath.length + 1)
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zos.close()
            tempDir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun restorePlatform(sourceZipFile: File): Result<Unit> {
        return try {
            val tempDir = File(warehouse.baseDir.parentFile, "restore_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            
            ZipInputStream(FileInputStream(sourceZipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(tempDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            
            val newWarehouse = File(tempDir, "warehouse")
            if (newWarehouse.exists()) {
                warehouse.baseDir.deleteRecursively()
                newWarehouse.copyRecursively(warehouse.baseDir, true)
            }
            
            val parent1 = warehouse.baseDir.parentFile
            val parent2 = parent1?.parentFile
            val newDb = File(tempDir, "database/localhost_loader.db")
            if (newDb.exists() && parent2 != null) {
                val dbFile = File(parent2, "databases/localhost_loader.db")
                dbFile.parentFile?.mkdirs()
                newDb.copyTo(dbFile, true)
            }
            
            val newUrlHistory = File(tempDir, "settings/url_history.json")
            if (newUrlHistory.exists()) {
                val urlHistoryFile = File(warehouse.baseDir.parentFile, "url_history.json")
                newUrlHistory.copyTo(urlHistoryFile, true)
            }
            
            val newAppPrefs = File(tempDir, "settings/app_prefs.xml")
            if (newAppPrefs.exists() && parent2 != null) {
                val appPrefsFile = File(parent2, "shared_prefs/app_prefs.xml")
                appPrefsFile.parentFile?.mkdirs()
                newAppPrefs.copyTo(appPrefsFile, true)
            }
            
            tempDir.deleteRecursively()

            // Trigger platform reconciliation to rebuild authoritative knowledge
            runBlocking {
                val authorityManager = StateAuthorityManager(warehouse)
                PlatformRecoveryEngine(warehouse, authorityManager).performRecovery()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generatePackCatalog(): String {
        val json = JSONObject()
        val runtimes = warehouse.runtimesDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val array = JSONArray()
        runtimes.forEach { r -> array.put(r.name) }
        json.put("packs", array)
        json.put("version", "1.0")
        return json.toString(2)
    }

    fun getOfflineRuntimeCatalog(): List<String> {
        val registry = try { JSONObject(warehouse.registryFile.readText()) } catch (e: Exception) { JSONObject() }
        val runtimes = mutableListOf<String>()
        registry.keys().forEach { runtimes.add(it) }
        return runtimes
    }

    fun computeOfflinePlan(requiredRuntime: String, requiredDeps: List<String>): JSONObject {
        val offlineCatalog = getOfflineRuntimeCatalog()
        val plan = JSONObject()
        plan.put("requiresNetwork", !offlineCatalog.contains(requiredRuntime))
        plan.put("missingRuntime", if (!offlineCatalog.contains(requiredRuntime)) requiredRuntime else null)
        val missingDeps = JSONArray()
        val depRegistry = try { JSONObject(warehouse.dependencyRegistryFile.readText()) } catch (e: Exception) { JSONObject() }
        val typeReg = depRegistry.optJSONObject(requiredRuntime) ?: JSONObject()
        
        requiredDeps.forEach { dep ->
            if (!typeReg.has(dep)) {
                missingDeps.put(dep)
            }
        }
        if (missingDeps.length() > 0) plan.put("requiresNetwork", true)
        plan.put("missingDependencies", missingDeps)
        return plan
    }
}
