package com.example.runtime

import java.io.File

class StaticAdapter : RuntimeAdapter {
    override val runtimeType = "static"

    override fun isAvailable(runtimeDir: File) = true

    override fun getInstalledVersion(runtimeDir: File): String? = "none"

    override fun installRuntime(runtimeDir: File, tempFile: File, downloadUrl: String, progressCallback: (Float, String) -> Unit): Result<Unit> {
        return Result.success(Unit)
    }

    override fun createIsolatedEnvironment(runtimeDir: File, appDir: File, basePath: String): Result<Environment> {
        return Result.success(Environment(basePath, mapOf()))
    }

    override fun installDependencies(env: Environment, dependencies: List<String>, cachePath: String): Result<Unit> {
        return Result.success(Unit)
    }

    override fun buildStartCommand(env: Environment, entryPoint: String, port: Int, envVars: Map<String, String>): List<String> {
        return listOf("internal:static", env.basePath)
    }

    override fun healthCheckCommand(port: Int): HealthCheck = HealthCheck.HttpCheck("/")

    override fun getDefaultEntryCandidates() = listOf("index.html")
}
