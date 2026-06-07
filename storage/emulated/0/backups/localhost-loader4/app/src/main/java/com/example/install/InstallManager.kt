package com.example.install

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.db.ServerEntity
import com.example.di.LocalhostApp
import com.example.pie.PackageIntelligenceEngine
import com.example.runtime.*
import com.example.util.DownloadUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class InstallProgress(val phase: String, val progress: Float, val subText: String = "")

object InstallManager {
    fun installFromFolder(context: Context, sourceFolderUri: Uri, name: String): Flow<InstallProgress> = flow {
        val appId = "app_" + UUID.randomUUID().toString()
        val appDir = File(LocalhostApp.warehousePath, "apps/$appId")
        val codeDir = File(appDir, "code").apply { mkdirs() }
        
        emit(InstallProgress("Copying Files", 0.1f, "Copying from selected folder..."))
        withContext(Dispatchers.IO) {
            val documentTree = DocumentFile.fromTreeUri(context, sourceFolderUri)
            copyDocumentFileRecursively(context, documentTree, codeDir)
        }
        
        emitAllInstallFlows(context, appId, name, appDir, codeDir)
    }

    fun installFromZip(context: Context, zipUri: Uri, name: String): Flow<InstallProgress> = flow {
        val appId = "app_" + UUID.randomUUID().toString()
        val appDir = File(LocalhostApp.warehousePath, "apps/$appId")
        val codeDir = File(appDir, "code").apply { mkdirs() }

        val runtimeWarehouse = RuntimeWarehouse(context)
        emit(InstallProgress("Extracting ZIP", 0.1f, "Unpacking archive..."))
        withContext(Dispatchers.IO) {
            val tempZip = runtimeWarehouse.createTempFile("archive", ".zip")
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }
            DownloadUtils.extractZip(tempZip, codeDir) {}
            tempZip.delete()
        }
        
        emitAllInstallFlows(context, appId, name, appDir, codeDir)
    }

    fun installFromUrl(context: Context, url: String, name: String): Flow<InstallProgress> = flow {
        val appId = "app_" + UUID.randomUUID().toString()
        val appDir = File(LocalhostApp.warehousePath, "apps/$appId")
        val codeDir = File(appDir, "code").apply { mkdirs() }

        val runtimeWarehouse = RuntimeWarehouse(context)
        emit(InstallProgress("Downloading Source", 0.1f, "Fetching from URL..."))
        withContext(Dispatchers.IO) {
            val tempZip = runtimeWarehouse.createTempFile("archive", ".zip")
            DownloadUtils.downloadFile(url, tempZip) { p ->
            }.getOrThrow()
            
            emit(InstallProgress("Extracting Source", 0.3f, "Unpacking downloaded archive..."))
            DownloadUtils.extractZip(tempZip, codeDir) {}
            tempZip.delete()
        }
        
        emitAllInstallFlows(context, appId, name, appDir, codeDir)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallProgress>.emitAllInstallFlows(context: Context, appId: String, name: String, appDir: File, codeDir: File) {
        try {
            emit(InstallProgress("Analyzing package", 0.3f, "Detecting framework..."))
            val plan = PackageIntelligenceEngine.analyze(codeDir)
            
            val runtimeWarehouse = RuntimeWarehouse(context)
            val manager = RuntimePackManager(runtimeWarehouse)
            
            emit(InstallProgress("Checking Runtime Engine", 0.5f, "Verifying ${plan.detectedRuntime}..."))
            val mirrorCatalog = com.example.runtime.catalog.MirrorCatalog(runtimeWarehouse)
            val runtimeCatalog = com.example.runtime.catalog.RuntimeCatalog(runtimeWarehouse, mirrorCatalog)
            val packUrl = runtimeCatalog.getRuntimeDownloadUrl(plan.detectedRuntime)
            val installResult = manager.installRuntimeIfNeeded(plan.detectedRuntime, packUrl) { p, msg ->
                // Let it install gracefully if missing
            }
            
            val adapter = manager.getAdapter(plan.detectedRuntime)!!
            val runtimeDir = runtimeWarehouse.getRuntimeDir(plan.detectedRuntime)
            if (installResult.isFailure) {
                 if (!adapter.isAvailable(runtimeDir)) {
                     emit(InstallProgress("Runtime Check Failed", 0.6f, "Could not acquire runtime."))
                     throw RuntimeException("Failed to install runtime: ${installResult.exceptionOrNull()?.message}")
                 }
            }
            
            emit(InstallProgress("Setting up environment", 0.7f, "Configuring ${plan.detectedRuntime}"))
            val envResult = adapter.createIsolatedEnvironment(runtimeDir, appDir, codeDir.absolutePath)
            val env = envResult.getOrThrow()
            
            if (plan.dependencies.isNotEmpty()) {
                emit(InstallProgress("Installing Dependencies", 0.8f, "${plan.dependencies.size} packages requested"))
                val cachePath = File(LocalhostApp.warehousePath, "${plan.detectedRuntime}-cache").apply { mkdirs() }.absolutePath
                withContext(Dispatchers.IO) {
                    adapter.installDependencies(env, plan.dependencies, cachePath).getOrThrow()
                    // Register deps
                    plan.dependencies.forEach { dep ->
                        runtimeWarehouse.registerDependency(plan.detectedRuntime, dep, "latest", appId)
                    }
                }
            }

            if (plan.preStartCommands.isNotEmpty()) {
                emit(InstallProgress("Building Application", 0.85f, "Running pre-start commands..."))
                withContext(Dispatchers.IO) {
                    plan.preStartCommands.forEach { cmdStr ->
                        val cmd = adapter.buildStartCommand(env, cmdStr, 0, emptyMap())
                        val pb = ProcessBuilder(cmd)
                        pb.environment().putAll(env.envVars)
                        pb.directory(codeDir)
                        val proc = pb.start()
                        val exit = proc.waitFor()
                        if (exit != 0) {
                            throw RuntimeException("Command failed: $cmdStr\n" + proc.errorStream.bufferedReader().readText())
                        }
                    }
                }
            }
            
            val manifestData = org.json.JSONObject().apply {
                put("framework", plan.framework)
                put("startupStrategy", plan.startupStrategy)
                put("healthCheckType", plan.healthCheckType)
                put("confidenceScore", plan.confidenceScore)
            }.toString(2)

            val entity = ServerEntity(
                id = appId,
                name = name.ifEmpty { plan.entryPoint.substringBeforeLast(".") },
                version = "1.0",
                runtimeType = plan.detectedRuntime,
                runtimeVersion = plan.runtimeVersion,
                entryPoint = plan.entryPoint,
                codePath = codeDir.absolutePath,
                envPath = env.basePath,
                status = "STOPPED",
                port = null,
                lastUsed = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
                iconPath = null,
                manifestJson = manifestData,
                capabilities = plan.capabilities.joinToString(", ")
            )
            
            LocalhostApp.db.serverDao().insertServer(entity)
            emit(InstallProgress("Done", 1.0f, "Ready to serve!"))
        } catch (e: Exception) {
            emit(InstallProgress("Failed", 1.0f, "Installation failed. Cleaning up..."))
            appDir.deleteRecursively()
            throw e
        }
    }

    private fun copyDocumentFileRecursively(context: Context, sourceDir: DocumentFile?, targetDir: File) {
        sourceDir?.listFiles()?.forEach { file ->
            val targetFile = File(targetDir, file.name ?: return@forEach)
            if (file.isDirectory) {
                targetFile.mkdirs()
                copyDocumentFileRecursively(context, file, targetFile)
            } else {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
