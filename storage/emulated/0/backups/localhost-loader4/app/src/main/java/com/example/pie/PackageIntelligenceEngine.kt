package com.example.pie

import org.json.JSONObject
import org.json.JSONArray
import java.io.File

data class PackageAnalysisRecord(
    val detectedRuntime: String,
    val runtimeVersion: String,
    val entryPoint: String,
    val dependencies: List<String>,
    val framework: String,
    val healthCheckType: String,
    val environmentVariables: Map<String, String>,
    val confidenceScore: Double,
    val requiresBuild: Boolean,
    val preStartCommands: List<String>,
    val postStartCommands: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val startupStrategy: String = "default"
)

class CapabilityConfidenceEngine {
    fun resolveCapabilities(framework: String, requiresNode: Boolean, requiresPython: Boolean): List<String> {
        val caps = mutableListOf<String>("BACKGROUND_SERVICE")
        if (requiresNode) caps.add("REQUIRES_NODE")
        if (requiresPython) caps.add("REQUIRES_PYTHON")
        when (framework) {
            "express", "fastapi", "django" -> caps.addAll(listOf("WEB_SERVER", "API_SERVER"))
            "nextjs", "php_base" -> caps.addAll(listOf("FULL_STACK", "WEB_SERVER", "API_SERVER"))
            "vite", "static" -> caps.addAll(listOf("WEB_SERVER", "STATIC_SITE"))
            "flask" -> caps.addAll(listOf("WEB_SERVER", "API_SERVER"))
        }
        return caps.distinct()
    }

    fun calculateConfidence(files: List<String>, matches: Int, explicit: Boolean): Double {
        if (explicit) return 1.0
        return (matches.toDouble() / files.size.toDouble() + 0.5).coerceAtMost(0.95)
    }
}

class StartupStrategyEngine {
    fun planStartup(framework: String, entryPoint: String): Pair<List<String>, String> {
        return when (framework) {
            "nextjs" -> Pair(listOf("npm run build"), "managed_build")
            "vite" -> Pair(emptyList(), "dev_server")
            "express", "django", "flask", "fastapi", "python_base", "node_base" -> Pair(emptyList(), "standard")
            else -> Pair(emptyList(), "direct")
        }
    }
}

class HealthStrategyEngine {
    fun planHealthCheck(framework: String): String {
        return if (framework in listOf("nextjs", "vite", "express", "fastapi", "django", "flask", "php_base", "static")) "http" else "port"
    }
}

class RuntimeRecommendationEngine {
    fun recommendVersion(runtime: String, reqs: List<String>): String {
        return when (runtime) {
            "python" -> "3.11"
            "node" -> "18"
            "php" -> "latest"
            else -> "none"
        }
    }
}

object PackageIntelligenceEngine {
    private val capabilityConfidenceEngine = CapabilityConfidenceEngine()
    private val startupStrategyEngine = StartupStrategyEngine()
    private val runtimeRecommendationEngine = RuntimeRecommendationEngine()
    private val healthStrategyEngine = HealthStrategyEngine()

    fun analyze(directory: File): PackageAnalysisRecord {
        if (!directory.exists() || !directory.isDirectory) return fallbackStatic()
        
        val manifestFile = File(directory, "loader.json")
        if (manifestFile.exists()) {
            try {
                val json = JSONObject(manifestFile.readText())
                val framework = json.optString("framework", "none")
                val (preCommands, strat) = startupStrategyEngine.planStartup(framework, json.optString("entryPoint", ""))
                return PackageAnalysisRecord(
                    detectedRuntime = json.optString("runtime", "static"),
                    runtimeVersion = json.optString("runtimeVersion", "none"),
                    entryPoint = json.optString("entryPoint", ""),
                    dependencies = json.optJSONArray("dependencies")?.let { arr -> List(arr.length()) { i -> arr.getString(i) } } ?: emptyList(),
                    framework = framework,
                    healthCheckType = json.optString("healthCheckType", healthStrategyEngine.planHealthCheck(framework)),
                    environmentVariables = json.optJSONObject("environmentVariables")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } } ?: emptyMap(),
                    confidenceScore = 1.0,
                    requiresBuild = json.optBoolean("requiresBuild", preCommands.isNotEmpty()),
                    preStartCommands = preCommands,
                    capabilities = capabilityConfidenceEngine.resolveCapabilities(framework, json.optString("runtime") == "node", json.optString("runtime") == "python"),
                    startupStrategy = strat
                )
            } catch (e: Exception) {}
        }

        val reqFile = File(directory, "requirements.txt")
        val pkgFile = File(directory, "package.json")
        val pyProject = File(directory, "pyproject.toml")
        val setupPy = File(directory, "setup.py")
        val indexHtml = File(directory, "index.html")
        val indexPhp = File(directory, "index.php")

        val result = if (indexPhp.exists()) {
            val (preCmd, strat) = startupStrategyEngine.planStartup("php_base", "index.php")
            PackageAnalysisRecord("php", "latest", "index.php", emptyList(), "php_base", healthStrategyEngine.planHealthCheck("php_base"), emptyMap(), capabilityConfidenceEngine.calculateConfidence(listOf("index.php"), 1, false), false, preCmd, emptyList(), capabilityConfidenceEngine.resolveCapabilities("php_base", false, false), strat)
        } else if (reqFile.exists() || pyProject.exists() || setupPy.exists() || directory.listFiles()?.any { it.name.endsWith(".py") } == true) {
            var reqs = emptyList<String>()
            if (reqFile.exists()) reqs = reqFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            val entryPointCandidate = listOf("app.py", "main.py", "wsgi.py", "manage.py", "server.py").firstOrNull { File(directory, it).exists() } ?: "app.py"
            val framework = if (reqs.any { it.contains("flask", true) } || File(directory, "templates").exists()) "flask" else if (reqs.any { it.contains("fastapi", true) }) "fastapi" else if (reqs.any { it.contains("django", true) } || File(directory, "manage.py").exists()) "django" else "python_base"
            
            var actualEntry = entryPointCandidate
            if (framework == "django" && entryPointCandidate == "manage.py") {
                actualEntry = "manage.py runserver 0.0.0.0:\$PORT --noreload"
            } else if (framework == "fastapi") {
                actualEntry = "-m uvicorn ${entryPointCandidate.removeSuffix(".py")}:app --host 0.0.0.0 --port \$PORT"
            } else if (framework == "flask") {
                actualEntry = "-m flask run --host=0.0.0.0 --port=\$PORT"
            }

            val (preCmd, strat) = startupStrategyEngine.planStartup(framework, actualEntry)
            PackageAnalysisRecord("python", runtimeRecommendationEngine.recommendVersion("python", reqs), actualEntry, reqs, framework, healthStrategyEngine.planHealthCheck(framework), mapOf("PORT" to "auto"), 0.9, false, preCmd, emptyList(), capabilityConfidenceEngine.resolveCapabilities(framework, false, true), strat)
        } else if (pkgFile.exists()) {
            val deps = mutableListOf<String>()
            var entryPoint = "index.js"
            var isNext = false; var isVite = false; var isExpress = false
            try {
                val pkgJson = JSONObject(pkgFile.readText())
                entryPoint = pkgJson.optString("main", "index.js")
                pkgJson.optJSONObject("dependencies")?.keys()?.forEach { deps.add(it); if (it == "next") isNext = true; if (it == "express") isExpress = true }
                pkgJson.optJSONObject("devDependencies")?.keys()?.forEach { deps.add(it); if (it == "vite") isVite = true }
            } catch (e: Exception) {}
            
            val framework = if(isNext) "nextjs" else if(isVite) "vite" else if(isExpress) "express" else "node_base"
            if (!isNext && !isVite && !File(directory, entryPoint).exists()) {
                listOf("server.js", "app.js", "main.js", "index.js").firstOrNull { File(directory, it).exists() }?.let { entryPoint = it }
            }
            if(isNext) entryPoint = "npm run start"
            if(isVite) entryPoint = "npm run dev -- --host --port \$PORT"
            
            val (preCmd, strat) = startupStrategyEngine.planStartup(framework, entryPoint)
            PackageAnalysisRecord("node", runtimeRecommendationEngine.recommendVersion("node", deps), entryPoint, deps, framework, healthStrategyEngine.planHealthCheck(framework), mapOf("PORT" to "auto"), 0.9, preCmd.isNotEmpty(), preCmd, emptyList(), capabilityConfidenceEngine.resolveCapabilities(framework, true, false), strat)
        } else if (indexHtml.exists()) {
            PackageAnalysisRecord("static", "none", "index.html", emptyList(), "static", healthStrategyEngine.planHealthCheck("static"), emptyMap(), 0.95, false, emptyList(), emptyList(), capabilityConfidenceEngine.resolveCapabilities("static", false, false), "direct")
        } else {
            fallbackStatic()
        }

        saveAnalysis(directory, result)
        return result
    }

    private fun saveAnalysis(directory: File, record: PackageAnalysisRecord) {
        val manifestFile = File(directory, "loader.json")
        try {
            val json = JSONObject().apply {
                put("runtime", record.detectedRuntime)
                put("runtimeVersion", record.runtimeVersion)
                put("entryPoint", record.entryPoint)
                put("dependencies", JSONArray(record.dependencies))
                put("framework", record.framework)
                put("healthCheckType", record.healthCheckType)
                put("environmentVariables", JSONObject(record.environmentVariables))
                put("requiresBuild", record.requiresBuild)
            }
            manifestFile.writeText(json.toString(2))
        } catch (e: Exception) {}
    }

    private fun fallbackStatic() = PackageAnalysisRecord("static", "none", "index.html", emptyList(), "none", "port", emptyMap(), 0.5, false, emptyList(), emptyList(), listOf("GENERIC_SERVER"), "direct")
}
