import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * D2 闭环指挥官：读取 D1 诊断报告，根据分类自动触发修正。
 * 完全确定性，不消耗 LLM。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    int maxRetry = 3
    try { maxRetry = Integer.parseInt((fn.str(fn.get("n_max_retry_cycles")) ?: "3").trim()) } catch (Throwable ignored) {}

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def analysisPath = base.resolve("test/equivalence/failure-analysis-${version}.json")

    if (!Files.isRegularFile(analysisPath)) {
        return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"no failure analysis found"]))
    }

    def analysis = JSON.parseObject(Files.readString(analysisPath, StandardCharsets.UTF_8))
    def failures = analysis.getJSONArray("failures") ?: new JSONArray()
    if (failures.isEmpty()) {
        return agentResult(JSON.toJSONString([status:"PASS", reason:"no failures to process"]))
    }

    // 读取反馈回路运行记录
    def runsPath = base.resolve("test/equivalence/feedback-loop-runs.json")
    def runs = new JSONArray()
    if (Files.isRegularFile(runsPath)) {
        try { runs = JSON.parseObject(Files.readString(runsPath, StandardCharsets.UTF_8)).getJSONArray("runs") ?: new JSONArray() } catch (Throwable ignored) {}
    }

    // 统计历史重译次数
    def history = [:]
    for (int i = 0; i < runs.size(); i++) {
        def run = runs.getJSONObject(i)
        def files = run.getJSONArray("goldenRetranslationFiles") ?: new JSONArray()
        for (int j = 0; j < files.size(); j++) {
            def f = files.getString(j)
            history[f] = (history[f] ?: 0) + 1
        }
    }

    int goldenRetranslations = 0
    int mappingResolves = 0
    int phase6Notifications = 0
    int mapGapWaivers = 0
    def escalations = []

    for (int i = 0; i < failures.size(); i++) {
        def f = failures.getJSONObject(i)
        def category = f.getString("category")
        def testName = f.getString("testName") ?: ""

        def parts = testName.split("_")
        def moduleId = parts.size() > 0 ? parts[0] : "unknown"
        def scenarioId = parts.size() > 1 ? parts[1] : "unknown"
        def scenarioRel = "verification/trace-scenarios/by-module/${moduleId}/${scenarioId}.scenario.json"

        switch (category) {
            case "GOLDEN_MISMATCH":
                def prev = history[scenarioRel] ?: 0
                if (prev >= maxRetry) {
                    escalations.add([scenarioRel: scenarioRel, reason:"GOLDEN_MISMATCH", retryCount: prev, action:"ESCALATE_TO_HUMAN"])
                } else {
                    markRetranslation(base, moduleId, scenarioId, analysisPath.toString())
                    goldenRetranslations++
                }
                break
            case "API_NOT_FOUND":
                markApiNotFound(base, moduleId, scenarioId)
                mappingResolves++
                break
            case "CODE_BUG":
                notifyPhase6(base, version, f)
                phase6Notifications++
                break
            case "MAP_GAP":
                registerWaiverCandidate(base, moduleId, scenarioId)
                mapGapWaivers++
                break
        }
    }

    // 写运行记录
    def currentRun = new JSONObject()
    currentRun.put("timestamp", Instant.now().toString())
    currentRun.put("goldenRetranslationCount", goldenRetranslations)
    currentRun.put("mappingResolveCount", mappingResolves)
    currentRun.put("phase6NotificationCount", phase6Notifications)
    currentRun.put("mapGapWaiverCount", mapGapWaivers)
    currentRun.put("escalationCount", escalations.size())

    def retransFiles = new JSONArray()
    for (int i = 0; i < failures.size(); i++) {
        def f = failures.getJSONObject(i)
        if (f.getString("category") == "GOLDEN_MISMATCH") {
            def p = f.getString("testName")?.split("_")
            retransFiles.add("verification/trace-scenarios/by-module/${p?.getAt(0) ?: 'unknown'}/${p?.getAt(1) ?: 'unknown'}.scenario.json")
        }
    }
    currentRun.put("goldenRetranslationFiles", retransFiles)
    runs.add(currentRun)

    def runsDoc = new JSONObject()
    runsDoc.put("schema", "cobol2java.feedback-loop-runs")
    runsDoc.put("version", version)
    runsDoc.put("projectKey", projectKey)
    runsDoc.put("maxRetryCycles", maxRetry)
    runsDoc.put("runs", runs)
    runsDoc.put("runCount", runs.size())
    Files.writeString(runsPath, JSON.toJSONString(runsDoc, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    // 写升级清单
    if (!escalations.isEmpty()) {
        def escPath = base.resolve("test/equivalence/feedback-loop-escalations.txt")
        def lines = ["以下场景超过最大修正次数(${maxRetry})，需人工介入:\n"]
        escalations.each { e -> lines.add("- ${e.scenarioRel} — ${e.reason} (${e.retryCount}次)") }
        Files.writeString(escPath, lines.join("\n"), StandardCharsets.UTF_8)
    }

    fn.put("n_feedback_loop_golden_retranslations", goldenRetranslations)
    fn.put("n_feedback_loop_escalations", escalations.size())

    return agentResult(JSON.toJSONString([
        status: "COMPLETE",
        goldenRetranslations: goldenRetranslations,
        mappingResolves: mappingResolves,
        phase6Notifications: phase6Notifications,
        mapGapWaivers: mapGapWaivers,
        escalations: escalations.size(),
        nextAction: goldenRetranslations > 0 ? "运行 7.0 以重译标记的 scenario" : "处理完成"
    ]))
}

static void markRetranslation(Path base, String moduleId, String scenarioId, String analysisPath) {
    def sp = base.resolve("verification/trace-scenarios/by-module/${moduleId}/${scenarioId}.scenario.json")
    if (!Files.isRegularFile(sp)) return
    try {
        def sc = JSON.parseObject(Files.readString(sp, StandardCharsets.UTF_8))
        def meta = sc.getJSONObject("goldenMetadata") ?: new JSONObject()
        meta.put("needsRetranslation", true)
        meta.put("retranslationReason", "GOLDEN_MISMATCH at ${Instant.now().toString()}")
        meta.put("retranslationSource", analysisPath)
        meta.put("retranslationMarkedAt", Instant.now().toString())
        def inc = sc.getJSONObject("incrementalStats") ?: new JSONObject()
        inc.put("skipCount", 100)
        inc.put("forceRetranslation", true)
        sc.put("incrementalStats", inc)
        sc.put("goldenMetadata", meta)
        Files.writeString(sp, JSON.toJSONString(sc), StandardCharsets.UTF_8)
    } catch (Throwable ignored) {}
}

static void markApiNotFound(Path base, String moduleId, String scenarioId) {
    def sp = base.resolve("verification/trace-scenarios/by-module/${moduleId}/${scenarioId}.scenario.json")
    if (!Files.isRegularFile(sp)) return
    try {
        def sc = JSON.parseObject(Files.readString(sp, StandardCharsets.UTF_8))
        def oq = sc.getJSONArray("openQuestions") ?: new JSONArray()
        def note = "API_NOT_FOUND at ${Instant.now().toString()}"
        if (!oq.contains(note)) oq.add(note)
        sc.put("openQuestions", oq)
        Files.writeString(sp, JSON.toJSONString(sc), StandardCharsets.UTF_8)
    } catch (Throwable ignored) {}
}

static void notifyPhase6(Path base, String version, JSONObject failure) {
    def dir = base.resolve("meta/runtime/recovery")
    Files.createDirectories(dir)
    def np = dir.resolve("phase7-code-bug-notifications.json")
    def notifications = new JSONArray()
    if (Files.isRegularFile(np)) {
        try { notifications = JSON.parseObject(Files.readString(np, StandardCharsets.UTF_8)).getJSONArray("notifications") ?: new JSONArray() } catch (Throwable ignored) {}
    }
    notifications.add(new JSONObject([
        timestamp: Instant.now().toString(),
        testName: failure.getString("testName") ?: "unknown",
        className: failure.getString("className") ?: "unknown",
        excerpt: failure.getString("excerpt")?.take(500),
        action: "Please repair Java code in Phase 6"
    ]))
    def doc = new JSONObject([schema:"cobol2java.phase7-code-bug-notifications", version:version, notifications:notifications, count:notifications.size()])
    Files.writeString(np, JSON.toJSONString(doc, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)
}

static void registerWaiverCandidate(Path base, String moduleId, String scenarioId) {
    def dir = base.resolve("meta/coverage")
    Files.createDirectories(dir)
    def wp = dir.resolve("waiver-candidates-auto.json")
    def candidates = new JSONArray()
    if (Files.isRegularFile(wp)) {
        try { candidates = JSON.parseObject(Files.readString(wp, StandardCharsets.UTF_8)).getJSONArray("candidates") ?: new JSONArray() } catch (Throwable ignored) {}
    }
    candidates.add(new JSONObject([
        scenarioId: scenarioId, moduleId: moduleId, category:"MAP_GAP",
        reason:"场景未映射到 API", registeredAt:Instant.now().toString(),
        source:"agt_feedback_loop_orchestrator.c2j.validate"
    ]))
    def doc = new JSONObject([schema:"cobol2java.waiver-candidates", version:"v1", candidates:candidates, count:candidates.size()])
    Files.writeString(wp, JSON.toJSONString(doc, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)
}
