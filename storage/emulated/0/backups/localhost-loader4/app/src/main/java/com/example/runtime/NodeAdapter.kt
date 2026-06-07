package com.example.runtime

import com.example.util.DownloadUtils
import java.io.File

class NodeAdapter : RuntimeAdapter {
    override val runtimeType = "node"

    private fun getBinary(runtimeDir: File, binName: String): File {
        val direct = File(runtimeDir, "bin/$binName")
        if (direct.exists()) return direct
        return File(runtimeDir, "$runtimeType/bin/$binName")
    }

    override fun isAvailable(runtimeDir: File): Boolean {
        val nodeBin = getBinary(runtimeDir, "node")
        return nodeBin.exists() && nodeBin.canExecute()
    }

    override fun getInstalledVersion(runtimeDir: File): String? {
        val nodeBin = getBinary(runtimeDir, "node")
        if (!nodeBin.exists()) return null
        return try {
            val process = ProcessBuilder(nodeBin.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    override fun installRuntime(runtimeDir: File, tempFile: File, downloadUrl: String, progressCallback: (Float, String) -> Unit): Result<Unit> {
        return Result.runCatching {
            progressCallback(0.1f, "Downloading Node runtime...")
            kotlinx.coroutines.runBlocking {
                DownloadUtils.downloadFile(downloadUrl, tempFile) { p ->
                    progressCallback(0.1f + p * 0.4f, "Downloading... ${(p * 100).toInt()}%")
                }.getOrThrow()
                
                progressCallback(0.5f, "Extracting Node runtime...")
                DownloadUtils.extractZip(tempFile, runtimeDir) { p ->
                    progressCallback(0.5f + p * 0.4f, "Extracting... ${(p * 100).toInt()}%")
                }.getOrThrow()
                tempFile.delete()
                
                getBinary(runtimeDir, "node").apply { setExecutable(true) }
                getBinary(runtimeDir, "npm").apply { setExecutable(true) }
            }
            progressCallback(1.0f, "Node runtime installed.")
        }
    }

    override fun createIsolatedEnvironment(runtimeDir: File, appDir: File, basePath: String): Result<Environment> {
        val envDir = File(appDir, "env")
        envDir.mkdirs()
        
        val nodeBinDir = getBinary(runtimeDir, "node").parentFile?.absolutePath ?: File(runtimeDir, "bin").absolutePath
        val envVars = mapOf(
            "PATH" to "$nodeBinDir:${System.getenv("PATH")}",
            "NODE_PATH" to "$basePath/node_modules"
        )
        return Result.success(Environment(envDir.absolutePath, envVars))
    }

    override fun installDependencies(env: Environment, dependencies: List<String>, cachePath: String): Result<Unit> {
        return Result.runCatching {
            val npmBin = env.envVars["PATH"]?.split(":")?.firstOrNull()?.let { File(it, "npm") }?.absolutePath 
                ?: throw RuntimeException("NPM bin not found")
            
            val hasPackageLock = File(env.basePath, "package-lock.json").exists()
            val installCmd = if (hasPackageLock) "ci" else "install"
            
            val pb = ProcessBuilder(npmBin, installCmd, "--cache", cachePath, "--prefix", env.basePath, "--no-audit", "--no-fund")
            pb.environment().putAll(env.envVars)
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.errorStream.bufferedReader().readText()
                throw RuntimeException("npm $installCmd failed: $err")
            }
        }
    }

    override fun buildStartCommand(env: Environment, entryPoint: String, port: Int, envVars: Map<String, String>): List<String> {
        val nodeBin = env.envVars["PATH"]?.split(":")?.firstOrNull()?.let { File(it, "node") }?.absolutePath ?: "node"
        val npmBin = env.envVars["PATH"]?.split(":")?.firstOrNull()?.let { File(it, "npm") }?.absolutePath ?: "npm"
        
        val parts = entryPoint.split(" ").filter { it.isNotBlank() }
        val parsedParts = parts.map { it.replace("\$PORT", port.toString()).replace("%PORT%", port.toString()) }
        
        return if (parsedParts.firstOrNull() == "npm") {
            listOf(npmBin) + parsedParts.drop(1)
        } else {
            listOf(nodeBin) + parsedParts
        }
    }

    override fun healthCheckCommand(port: Int): HealthCheck = HealthCheck.PortCheck()

    override fun getDefaultEntryCandidates() = listOf("server.js", "index.js", "app.js", "main.js")
}
