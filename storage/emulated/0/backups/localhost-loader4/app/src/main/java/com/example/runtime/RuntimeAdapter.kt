package com.example.runtime

import java.io.File

data class Environment(val basePath: String, val envVars: Map<String, String>)

sealed class HealthCheck {
    data class PortCheck(val timeoutMs: Long = 500) : HealthCheck()
    data class HttpCheck(val path: String = "/", val expectedStatus: Int = 200) : HealthCheck()
    object ProcessAlive : HealthCheck()
}

interface RuntimeAdapter {
    val runtimeType: String
    fun isAvailable(runtimeDir: File): Boolean
    fun getInstalledVersion(runtimeDir: File): String?
    fun installRuntime(runtimeDir: File, tempFile: File, downloadUrl: String, progressCallback: (Float, String) -> Unit): Result<Unit>
    fun createIsolatedEnvironment(runtimeDir: File, appDir: File, basePath: String): Result<Environment>
    fun installDependencies(env: Environment, dependencies: List<String>, cachePath: String): Result<Unit>
    fun buildStartCommand(env: Environment, entryPoint: String, port: Int, envVars: Map<String, String>): List<String>
    fun healthCheckCommand(port: Int): HealthCheck
    fun getDefaultEntryCandidates(): List<String>
}
