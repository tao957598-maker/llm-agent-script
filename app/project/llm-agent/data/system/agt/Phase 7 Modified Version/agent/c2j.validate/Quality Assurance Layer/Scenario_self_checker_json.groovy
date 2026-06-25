import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def blockOnMissingGolden = (fn.str(fn.get("n_block_on_missing_golden")) ?: "true").trim() == "true"
    def blockOnMissingModuleId = (fn.str(fn.get("n_block_on_missing_module_id")) ?: "true").trim() == "true"

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
    def warnings = []
    def blocked = []
    int totalScanned = 0
    int passed = 0

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
            .forEach { p ->
                totalScanned++
                def rel = base.relativize(p).toString()
                def scenarioId = p.getFileName().toString().replace(".scenario.json", "")
                def fileIssues = []

                try {
                    def sc = JSON.parseObject(Files.readString(p, StandardCharsets.UTF_8))

                    // 检查 moduleId
                    def moduleId = sc.getString("moduleId")
                    if (!moduleId || moduleId.isBlank()) {
                        if (blockOnMissingModuleId) {
                            fileIssues.add([severity: "critical", check: "missing-moduleId",
                                detail: "场景 ${scenarioId} 缺少 moduleId"])
                            blocked.add(rel)
                        } else {
                            fileIssues.add([severity: "warning", check: "missing-moduleId"])
                        }
                    }

                    // 检查 golden 是否为空
                    def golden = sc.getJSONObject("goldenApiResponse")
                    def steps = sc.getJSONArray("steps") ?: new JSONArray()
                    if ((golden == null || golden.isEmpty()) && !steps.isEmpty()) {
                        if (blockOnMissingGolden) {
                            fileIssues.add([severity: "critical", check: "missing-golden",
                                detail: "有 ${steps.size()} 个步骤但 golden 为空"])
                            blocked.add(rel)
                        } else {
                            fileIssues.add([severity: "warning", check: "missing-golden"])
                        }
                    }

                    // 检查 golden 中字符串数字
                    if (golden != null && !golden.isEmpty()) {
                        golden.keySet().each { key ->
                            def val = golden.get(key)
                            if (val instanceof String && val ==~ /^\d+(\.\d+)?$/) {
                                fileIssues.add([severity: "warning", check: "string-number",
                                    detail: "字段 '${key}' = \"${val}\" 是字符串而非数字"])
                            }
                        }
                    }

                    // 检查 API 路径格式
                    def hints = sc.getJSONArray("apiMappingHints") ?: new JSONArray()
                    for (int i = 0; i < hints.size(); i++) {
                        def path = hints.getJSONObject(i)?.getString("path") ?: ""
                        if (!path.isBlank() && !path.startsWith("/")) {
                            fileIssues.add([severity: "warning", check: "invalid-api-path",
                                detail: "路径 '${path}' 不以 / 开头"])
                        }
                    }

                    // 检查 scenarioId
                    if (!sc.getString("scenarioId")?.trim()) {
                        fileIssues.add([severity: "warning", check: "missing-scenarioId"])
                    }

                    // 检查 confidence 范围
                    def confidence = sc.getDouble("confidence")
                    if (confidence != null && (confidence < 0 || confidence > 1)) {
                        fileIssues.add([severity: "warning", check: "invalid-confidence",
                            detail: "confidence=${confidence}"])
                    }
                } catch (Throwable t) {
                    fileIssues.add([severity: "critical", check: "parse-error", detail: t.message])
                    blocked.add(rel)
                }

                if (!fileIssues.isEmpty()) warnings.add([scenarioRel: rel, scenarioId: scenarioId, issues: fileIssues])
                if (!blocked.contains(rel)) passed++
            }
    }

    def report = [
        schema: "cobol2java.scenario-self-check-report",
        version: "v1",
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_scenario_self_checker.c2j.validate",
        totalScanned: totalScanned,
        passed: passed,
        blockedCount: blocked.size(),
        warningCount: warnings.size(),
        blockedFiles: blocked,
        warnings: warnings,
        summary: blocked.isEmpty()
            ? "PASS — 所有 ${totalScanned} 个 scenario 通过检查"
            : "BLOCKED — ${blocked.size()} 个 scenario 被阻止落盘"
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("scenario-self-check-report-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    if (!blocked.isEmpty()) {
        Files.writeString(reportDir.resolve("scenario-self-check-blocked.txt"),
            "需重译:\n" + blocked.join("\n"), StandardCharsets.UTF_8)
    }

    fn.put("n_scenario_self_check_total", totalScanned)
    fn.put("n_scenario_self_check_passed", passed)
    fn.put("n_scenario_self_check_blocked", blocked.size())

    return agentResult(JSON.toJSONString([
        status: blocked.isEmpty() ? "PASS" : "BLOCKED",
        totalScanned: totalScanned,
        passed: passed,
        blocked: blocked.size(),
        warnings: warnings.size()
    ]))
}
