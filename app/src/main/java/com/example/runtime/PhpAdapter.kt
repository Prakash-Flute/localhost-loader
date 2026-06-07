package com.example.runtime

import com.example.util.DownloadUtils
import java.io.File

class PhpAdapter : RuntimeAdapter {
    override val runtimeType = "php"

    private fun getBinary(runtimeDir: File, binName: String): File {
        val direct = File(runtimeDir, "bin/$binName")
        if (direct.exists()) return direct
        return File(runtimeDir, "$runtimeType/bin/$binName")
    }

    override fun isAvailable(runtimeDir: File): Boolean {
        val phpBin = getBinary(runtimeDir, "php")
        return phpBin.exists() && phpBin.canExecute()
    }

    override fun getInstalledVersion(runtimeDir: File): String? {
        val phpBin = getBinary(runtimeDir, "php")
        if (!phpBin.exists()) return null
        return try {
            val process = ProcessBuilder(phpBin.absolutePath, "-v")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }.trim().split("\n").firstOrNull()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    override fun installRuntime(runtimeDir: File, tempFile: File, downloadUrl: String, progressCallback: (Float, String) -> Unit): Result<Unit> {
        return Result.runCatching {
            progressCallback(0.1f, "Downloading PHP runtime...")
            kotlinx.coroutines.runBlocking {
                DownloadUtils.downloadFile(downloadUrl, tempFile) { p ->
                    progressCallback(0.1f + p * 0.4f, "Downloading... ${(p * 100).toInt()}%")
                }.getOrThrow()
                
                progressCallback(0.5f, "Extracting PHP runtime...")
                DownloadUtils.extractZip(tempFile, runtimeDir) { p ->
                    progressCallback(0.5f + p * 0.4f, "Extracting... ${(p * 100).toInt()}%")
                }.getOrThrow()
                tempFile.delete()
                
                getBinary(runtimeDir, "php").apply { setExecutable(true) }
            }
            progressCallback(1.0f, "PHP runtime installed.")
        }
    }

    override fun createIsolatedEnvironment(runtimeDir: File, appDir: File, basePath: String): Result<Environment> {
        val envDir = File(appDir, "env")
        envDir.mkdirs()
        
        val phpBinDir = getBinary(runtimeDir, "php").parentFile?.absolutePath ?: File(runtimeDir, "bin").absolutePath
        val envVars = mapOf(
            "PATH" to "$phpBinDir:${System.getenv("PATH")}"
        )
        return Result.success(Environment(envDir.absolutePath, envVars))
    }

    override fun installDependencies(env: Environment, dependencies: List<String>, cachePath: String): Result<Unit> {
        return Result.success(Unit)
    }

    override fun buildStartCommand(env: Environment, entryPoint: String, port: Int, envVars: Map<String, String>): List<String> {
        val phpBin = env.envVars["PATH"]?.split(":")?.firstOrNull()?.let { File(it, "php") }?.absolutePath ?: "php"
        return listOf(phpBin, "-S", "0.0.0.0:$port", "-t", env.basePath)
    }

    override fun healthCheckCommand(port: Int): HealthCheck = HealthCheck.PortCheck()

    override fun getDefaultEntryCandidates() = listOf("index.php")
}
