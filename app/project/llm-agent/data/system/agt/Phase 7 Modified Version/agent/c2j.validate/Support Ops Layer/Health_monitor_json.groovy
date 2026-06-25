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
 * F1 值班员：收集 Phase 7 各 Agent 的执行指标，检测异常模式。
 * 完全确定性——文件读取 + 阈值比较。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    int alertRetranslation = 3
    int alertDeadLink = 5
    double alertSkipRate = 0.9
    try { alertRetranslation = Integer.parseInt((fn.str(fn.get("n_alert_threshold_retranslation")) ?: "3").trim()) } catch (Throwable ignored) {}
    try { alertDeadLink = Integer.parseInt((fn.str(fn.get("n_alert_threshold_dead_link")) ?: "5").trim()) } catch (Throwable ignored) {}
    try { alertSkipRate = Double.parseDouble((fn.str(fn.get("n_alert_threshold_skip_rate")) ?: "0.9").trim()) } catch (Throwable ignored) {}

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")
    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()

    def readJson = { String r ->
        def p = base.resolve(r.replace("/", java.io.File.separator))
        if (!Files.isRegularFile(p)) return new JSONObject()
        try { return JSON.parseObject(Files.readString(p, StandardCharsets.UTF_8)) } catch (Throwable ignored) { return new JSONObject() }
    }

    def now = Instant.now()
    def alerts = []
    def metrics = [:]

    // ---- 1. 检查增量死锁：NEEDS_RETRANSLATION 场景数 ----
    def trust = readJson("test/equivalence/golden-trust-assessment-v1.json")
    def trustStats = trust.getJSONObject("stats") ?: new JSONObject()
    int retranslationCount = trustStats.getIntValue("needsRetranslationCount")
    metrics.goldenTrustRetranslation = retranslationCount
    if (retranslationCount >= alertRetranslation) {
        alerts.add([severity:"warning", source:"golden_trust_assessor",
                    metric:"needsRetranslationCount", value:retranslationCount,
                    threshold:alertRetranslation, message:"待重译 scenario 数量 (${retranslationCount}) 达到告警阈值"])
    }

    // ---- 2. 检查反馈回路：连续修正升级数 ----
    def feedbackRuns = readJson("test/equivalence/feedback-loop-runs.json")
    def runs = feedbackRuns.getJSONArray("runs") ?: new JSONArray()
    metrics.feedbackLoopRuns = runs.size()
    int escalations = 0
    for (int i = 0; i < runs.size(); i++) {
        escalations += runs.getJSONObject(i)?.getIntValue("escalationCount") ?: 0
    }
    metrics.escalations = escalations
    if (escalations > 0) {
        alerts.add([severity:"critical", source:"feedback_loop_orchestrator",
                    metric:"escalations", value:escalations,
                    threshold:1, message:"有 ${escalations} 个场景需人工介入"])
    }

    // ---- 3. 检查 catalog 死链 ----
    def catalog = readJson("verification/trace-scenarios/trace-catalog.json")
    def modules = catalog.getJSONArray("modules") ?: new JSONArray()
    int deadLinks = 0
    for (int i = 0; i < modules.size(); i++) {
        def m = modules.getJSONObject(i)
        def scenarioRel = m?.getJSONObject("trace")?.getString("scenario")
        if (scenarioRel != null && !scenarioRel.isBlank()) {
            def sp = base.resolve(scenarioRel.replace("/", java.io.File.separator))
            if (!Files.isRegularFile(sp)) deadLinks++
        }
    }
    metrics.catalogDeadLinks = deadLinks
    if (deadLinks >= alertDeadLink) {
        alerts.add([severity:"critical", source:"trace-catalog",
                    metric:"deadLinks", value:deadLinks,
                    threshold:alertDeadLink, message:"catalog 死链数量 (${deadLinks}) 达到告警阈值"])
    }

    // ---- 4. 检查覆盖率趋势 ----
    def coverage = readJson("test/equivalence/coverage-analysis-v1.json")
    def covSummary = coverage.getJSONObject("summary") ?: new JSONObject()
    int totalBranches = covSummary.getIntValue("totalBranches")
    int coveredBranches = covSummary.getIntValue("coveredBranches")
    double covRate = totalBranches > 0 ? coveredBranches * 100.0 / totalBranches : 0
    metrics.coverageRate = Math.round(covRate * 10) / 10.0
    if (covRate < 60 && totalBranches > 0) {
        alerts.add([severity:"warning", source:"coverage_analyzer",
                    metric:"coverageRate", value:Math.round(covRate * 10) / 10.0,
                    threshold:60, message:"分支覆盖率 (${Math.round(covRate)}%) 低于 60%"])
    }

    // ---- 5. 检查跳过率 ----
    def selfCheck = readJson("test/equivalence/scenario-self-check-report-v1.json")
    int totalScanned = selfCheck.getIntValue("totalScanned")
    int blocked = selfCheck.getIntValue("blockedCount")
    metrics.selfCheckBlocked = blocked
    metrics.selfCheckTotal = totalScanned
    double skipRate = totalScanned > 0 ? blocked * 1.0 / totalScanned : 0
    if (skipRate >= alertSkipRate && totalScanned > 0) {
        alerts.add([severity:"warning", source:"scenario_self_checker",
                    metric:"skipRate", value:Math.round(skipRate * 100),
                    threshold:Math.round(alertSkipRate * 100),
                    message:"自检拦截率 (${Math.round(skipRate*100)}%) 过高"])
    }

    // ---- 6. 写健康报告 ----
    def report = [
        schema: "cobol2java.phase7-health-report",
        version: "v1",
        projectKey: projectKey,
        generatedAt: now.toString(),
        generatedBy: "agt_phase7_health_monitor.c2j.validate",
        status: alerts.any { it.severity == "critical" } ? "CRITICAL"
            : alerts.isEmpty() ? "HEALTHY" : "WARNING",
        metrics: metrics,
        alerts: alerts,
        alertCount: alerts.size()
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("phase7-health-report-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_health_monitor_alerts", alerts.size())
    fn.put("n_health_monitor_status", report.status)

    return agentResult(JSON.toJSONString([
        status: report.status,
        alerts: alerts.size(),
        metrics: [retranslationCount: retranslationCount, deadLinks: deadLinks,
                  coverageRate: covRate, escalations: escalations]
    ]))
}
