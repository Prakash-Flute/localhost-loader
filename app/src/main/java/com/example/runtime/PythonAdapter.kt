package com.example.runtime

import com.example.util.DownloadUtils
import java.io.File

class PythonAdapter : RuntimeAdapter {
    override val runtimeType = "python"

    private fun getBinary(runtimeDir: File, binName: String): File {
        val direct = File(runtimeDir, "bin/$binName")
        if (direct.exists()) return direct
        return File(runtimeDir, "$runtimeType/bin/$binName")
    }

    override fun isAvailable(runtimeDir: File): Boolean {
        val pythonBin = getBinary(runtimeDir, "python")
        return pythonBin.exists() && pythonBin.canExecute()
    }

    override fun getInstalledVersion(runtimeDir: File): String? {
        val pythonBin = getBinary(runtimeDir, "python")
        if (!pythonBin.exists()) return null
        return try {
            val process = ProcessBuilder(pythonBin.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    override fun installRuntime(runtimeDir: File, tempFile: File, downloadUrl: String, progressCallback: (Float, String) -> Unit): Result<Unit> {
        return Result.runCatching {
            progressCallback(0.1f, "Downloading Python runtime...")
            kotlinx.coroutines.runBlocking {
                DownloadUtils.downloadFile(downloadUrl, tempFile) { p ->
                    progressCallback(0.1f + p * 0.4f, "Downloading... ${(p * 100).toInt()}%")
                }.getOrThrow()
                
                progressCallback(0.5f, "Extracting Python runtime...")
                DownloadUtils.extractZip(tempFile, runtimeDir) { p ->
                    progressCallback(0.5f + p * 0.4f, "Extracting... ${(p * 100).toInt()}%")
                }.getOrThrow()
                tempFile.delete()
                
                getBinary(runtimeDir, "python").apply { setExecutable(true) }
                getBinary(runtimeDir, "pip").apply { setExecutable(true) }
            }
            progressCallback(1.0f, "Python runtime installed.")
        }
    }

    override fun createIsolatedEnvironment(runtimeDir: File, appDir: File, basePath: String): Result<Environment> {
        val envDir = File(appDir, "venv")
        val pythonBin = getBinary(runtimeDir, "python").absolutePath
        
        return Result.runCatching {
            if (!envDir.exists()) {
                val process = ProcessBuilder(pythonBin, "-m", "venv", envDir.absolutePath).start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val err = process.errorStream.bufferedReader().readText()
                    throw RuntimeException("venv creation failed: $err")
                }
            }
            val venvBin = File(envDir, "bin")
            val envVars = mapOf(
                "PATH" to "${venvBin.absolutePath}:${System.getenv("PATH")}",
                "VIRTUAL_ENV" to envDir.absolutePath
            )
            Environment(envDir.absolutePath, envVars)
        }
    }

    override fun installDependencies(env: Environment, dependencies: List<String>, cachePath: String): Result<Unit> {
        if (dependencies.isEmpty()) return Result.success(Unit)
        
        return Result.runCatching {
            val pipBin = env.envVars["PATH"]?.split(":")?.firstOrNull()?.let { File(it, "pip") }?.absolutePath 
                ?: throw RuntimeException("pip bin not found")
            val reqFile = File.createTempFile("req", ".txt")
            reqFile.writeText(dependencies.joinToString("\n"))
            
            val pb = ProcessBuilder(pipBin, "install", "--cache-dir", cachePath, "-r", reqFile.absolutePath, "--disable-pip-version-check")
            pb.environment().putAll(env.envVars)
            val proc = pb.start()
            val exitCode = proc.waitFor()
            reqFile.delete()
            
            if (exitCode != 0) {
                val err = proc.errorStream.bufferedReader().readText()
                throw RuntimeException("pip install failed: $err")
            }
        }
    }

    override fun buildStartCommand(env: Environment, entryPoint: String, port: Int, envVars: Map<String, String>): List<String> {
        val pythonBin = env.envVars["PATH"]?.split(":")?.firstOrNull()?.let { File(it, "python") }?.absolutePath ?: "python"
        val parts = entryPoint.split(" ").filter { it.isNotBlank() }
        val parsedParts = parts.map { it.replace("\$PORT", port.toString()).replace("%PORT%", port.toString()) }
        return listOf(pythonBin) + parsedParts
    }

    override fun healthCheckCommand(port: Int): HealthCheck = HealthCheck.PortCheck()

    override fun getDefaultEntryCandidates() = listOf("app.py", "main.py", "wsgi.py", "manage.py")
}
