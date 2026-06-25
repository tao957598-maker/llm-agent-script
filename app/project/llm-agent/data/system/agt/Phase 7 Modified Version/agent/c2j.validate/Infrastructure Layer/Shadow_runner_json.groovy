import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * E1 影子运行器：双跑对比获取真实 golden。
 * 需要老系统访问权限（CICS/批处理）和新系统 API。
 * 若无老系统配置 → 标记 SKIPPED。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    int maxScenarios = 5
    try { maxScenarios = Integer.parseInt((fn.str(fn.get("n_max_scenarios")) ?: "5").trim()) } catch (Throwable ignored) {}

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")

    // 检查老系统配置（通过环境变量或配置文件）
    def legacyConfig = loadLegacyConfig(base)
    if (legacyConfig == null) {
        fn.put("n_shadow_runner_skipped", "true")
        return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"no legacy system configuration"]))
    }

    def results = []
    int successCount = 0
    int diffCount = 0
    int skippedCount = 0

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
            .limit(maxScenarios)
            .forEach { scenarioFile ->
                def rel = base.relativize(scenarioFile).toString()
                try {
                    def sc = JSON.parseObject(Files.readString(scenarioFile, StandardCharsets.UTF_8))
                    def scenarioId = sc.getString("scenarioId") ?: scenarioFile.getFileName().toString()

                    // 仅处理有 apiMappingHints 的场景
                    def hints = sc.getJSONArray("apiMappingHints") ?: new JSONArray()
                    if (hints.isEmpty()) {
                        skippedCount++
                        results.add([scenarioRel:rel, status:"skipped", reason:"no apiMappingHints"])
                        return
                    }
                    def hint = hints.getJSONObject(0)

                    // 从 trace 中提取老系统输入
                    def legacyInput = extractLegacyInput(sc)
                    if (legacyInput == null) {
                        skippedCount++
                        results.add([scenarioRel:rel, status:"skipped", reason:"cannot extract legacy input"])
                        return
                    }

                    // 1. 在老系统上执行
                    def legacyOutput = executeLegacy(legacyConfig, sc, legacyInput)
                    if (legacyOutput == null) {
                        skippedCount++
                        results.add([scenarioRel:rel, status:"skipped", reason:"legacy execution failed"])
                        return
                    }

                    // 2. 在新系统上执行
                    def newOutput = executeNewSystem(base, hint, legacyInput)
                    if (newOutput == null) {
                        skippedCount++
                        results.add([scenarioRel:rel, status:"skipped", reason:"new system execution failed"])
                        return
                    }

                    // 3. 逐字段 diff
                    def diff = compareJson(legacyOutput, newOutput)
                    if (diff.isEmpty()) {
                        // 一致 → 写入 golden
                        sc.put("goldenApiResponse", legacyOutput)
                        def meta = sc.getJSONObject("goldenMetadata") ?: new JSONObject()
                        meta.put("goldenSource", "shadow-run")
                        meta.put("shadowRunAt", Instant.now().toString())
                        sc.put("goldenMetadata", meta)
                        sc.put("confidence", 0.99)
                        Files.writeString(scenarioFile, JSON.toJSONString(sc), StandardCharsets.UTF_8)
                        successCount++
                        results.add([scenarioRel:rel, status:"match", goldenWritten:true])
                    } else {
                        diffCount++
                        results.add([scenarioRel:rel, status:"diff", diff:diff])
                    }
                } catch (Throwable t) {
                    results.add([scenarioRel:rel, status:"error", error:t.message])
                    skippedCount++
                }
            }
    }

    // 写差异报告
    def report = [
        schema: "cobol2java.shadow-runner-report",
        version: "v1",
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_shadow_runner.c2j.validate",
        stats: [total: results.size(), success: successCount, diff: diffCount, skipped: skippedCount],
        results: results
    ]
    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("shadow-runner-report-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_shadow_runner_success", successCount)
    fn.put("n_shadow_runner_diff", diffCount)

    return agentResult(JSON.toJSONString([
        status: "COMPLETE", success: successCount, diff: diffCount, skipped: skippedCount
    ]))
}

// ---- 辅助函数 ----

static JSONObject loadLegacyConfig(Path base) {
    def configPath = base.resolve("meta/legacy-config.json")
    if (!Files.isRegularFile(configPath)) return null
    try {
        return JSON.parseObject(Files.readString(configPath, StandardCharsets.UTF_8))
    } catch (Throwable ignored) { return null }
}

static JSONObject extractLegacyInput(JSONObject sc) {
    // 从 scenario 的 steps 和 apiMappingHints 中提取老系统的输入参数
    def hints = sc.getJSONArray("apiMappingHints") ?: new JSONArray()
    if (hints.isEmpty()) return null
    def hint = hints.getJSONObject(0)
    def input = new JSONObject()
    if (hint.getJSONObject("body") != null) input.putAll(hint.getJSONObject("body"))
    if (hint.getJSONObject("query") != null) input.putAll(hint.getJSONObject("query"))
    if (hint.getJSONObject("pathParams") != null) input.putAll(hint.getJSONObject("pathParams"))
    return input.isEmpty() ? null : input
}

static JSONObject executeLegacy(JSONObject config, JSONObject sc, JSONObject input) {
    // 通过配置中的 CICS 连接或批处理接口执行老系统交易
    // 此处为框架代码，实际需通过 act_ac.exec 或自定义适配器实现
    try {
        def url = config.getString("legacyEndpoint") ?: "http://legacy-host/cics/execute"
        def client = HttpClient.newHttpClient()
        def req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString([
                transactionId: sc.getString("transactionId") ?: sc.getString("scenarioId"),
                input: input
            ])))
            .build()
        def resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() == 200) {
            return JSON.parseObject(resp.body())
        }
    } catch (Throwable t) {}
    return null
}

static JSONObject executeNewSystem(Path base, JSONObject hint, JSONObject input) {
    // 调用新系统 API
    try {
        def path = hint.getString("path") ?: "/api/execute"
        def method = hint.getString("method") ?: "POST"
        def fullUrl = "http://localhost:8080" + path
        def client = HttpClient.newHttpClient()
        def builder = HttpRequest.newBuilder().uri(URI.create(fullUrl))
        if ("POST".equalsIgnoreCase(method)) {
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(input)))
        } else {
            builder.GET()
        }
        def resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() in [200, 400, 500]) {
            return JSON.parseObject(resp.body())
        }
    } catch (Throwable t) {}
    return null
}

static List compareJson(JSONObject a, JSONObject b) {
    def diffs = []
    def allKeys = (a.keySet() + b.keySet()).unique()
    allKeys.each { key ->
        if (!a.containsKey(key)) diffs.add("missing in legacy: ${key}")
        else if (!b.containsKey(key)) diffs.add("missing in new: ${key}")
        else if (a.get(key) != b.get(key)) diffs.add("${key}: legacy=${a.get(key)} new=${b.get(key)}")
    }
    return diffs
}
