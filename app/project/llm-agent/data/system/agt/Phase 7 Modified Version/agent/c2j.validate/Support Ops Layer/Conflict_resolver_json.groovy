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
 * F2 调解员：检测并解决 scenario 文件路径冲突。
 * 完全确定性——文件路径检查 + 重命名。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def strategy = (fn.str(fn.get("n_conflict_strategy")) ?: "rename").trim()

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")
    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")

    if (!Files.isDirectory(scenarioDir)) {
        return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"no scenario directory"]))
    }

    // 收集所有 scenario，按 moduleId+scenarioId 分组检测冲突
    def scenarioMap = [:]

    Files.walk(scenarioDir)
        .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
        .forEach { p ->
            def rel = base.relativize(p).toString()
            try {
                def sc = JSON.parseObject(Files.readString(p, StandardCharsets.UTF_8))
                def moduleId = sc.getString("moduleId") ?: p.getParent().getFileName().toString()
                def scenarioId = sc.getString("scenarioId") ?: p.getFileName().toString().replace(".scenario.json", "")
                def traceFile = sc.getString("traceFile") ?: ""

                def key = "${moduleId}/${scenarioId}"
                if (!scenarioMap.containsKey(key)) scenarioMap[key] = []
                scenarioMap[key].add([rel:rel, traceFile:traceFile, scenarioId:scenarioId, moduleId:moduleId])
            } catch (Throwable ignored) {}
        }

    int conflictsResolved = 0
    int conflictsMerged = 0
    def conflictDetails = []

    scenarioMap.each { key, files ->
        if (files.size() <= 1) return

        def sorted = files.sort { it.rel }
        def primary = sorted[0]

        for (int i = 1; i < sorted.size(); i++) {
            def conflict = sorted[i]
            def detail = [key:key, primary:primary.rel, conflict:conflict.rel, traceFiles:[primary.traceFile, conflict.traceFile]]

            if (strategy == "merge") {
                // 合并 steps/hints 到主文件
                try {
                    def primarySc = JSON.parseObject(Files.readString(base.resolve(primary.rel), StandardCharsets.UTF_8))
                    def conflictSc = JSON.parseObject(Files.readString(base.resolve(conflict.rel), StandardCharsets.UTF_8))

                    def primarySteps = primarySc.getJSONArray("steps") ?: new JSONArray()
                    def conflictSteps = conflictSc.getJSONArray("steps") ?: new JSONArray()
                    for (int j = 0; j < conflictSteps.size(); j++) primarySteps.add(conflictSteps.get(j))
                    primarySc.put("steps", primarySteps)

                    def primaryHints = primarySc.getJSONArray("apiMappingHints") ?: new JSONArray()
                    def conflictHints = conflictSc.getJSONArray("apiMappingHints") ?: new JSONArray()
                    for (int j = 0; j < conflictHints.size(); j++) primaryHints.add(conflictHints.get(j))
                    primarySc.put("apiMappingHints", primaryHints)

                    // 记录多来源
                    def sources = primarySc.getJSONArray("sourceTraces") ?: new JSONArray()
                    sources.add(conflict.traceFile)
                    primarySc.put("sourceTraces", sources)

                    Files.writeString(base.resolve(primary.rel), JSON.toJSONString(primarySc), StandardCharsets.UTF_8)
                    Files.deleteIfExists(base.resolve(conflict.rel))
                    detail.action = "merged"
                    conflictsMerged++
                } catch (Throwable t) {
                    detail.action = "merge-failed"
                    detail.error = t.message
                    // 合并失败时回退到 rename
                    renameConflict(base, conflict, i)
                    detail.action = "renamed-after-merge-fail"
                }
            } else {
                renameConflict(base, conflict, i)
                detail.action = "renamed"
                conflictsResolved++
            }
            conflictDetails.add(detail)
        }
    }

    def report = [
        schema: "cobol2java.scenario-conflict-resolution-report",
        version: "v1",
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_scenario_conflict_resolver.c2j.validate",
        strategy: strategy,
        stats: [conflictsDetected: conflictDetails.size(), conflictsResolved: conflictsResolved,
                conflictsMerged: conflictsMerged],
        details: conflictDetails
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("scenario-conflict-report-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_conflict_resolver_total", conflictDetails.size())
    fn.put("n_conflict_resolver_resolved", conflictsResolved + conflictsMerged)
    return agentResult(JSON.toJSONString([
        status:"COMPLETE", conflicts:conflictDetails.size(),
        resolved:conflictsResolved, merged:conflictsMerged
    ]))
}

static void renameConflict(Path base, Map conflict, int suffix) {
    def oldPath = base.resolve(conflict.rel)
    def parent = oldPath.getParent()
    def baseName = conflict.scenarioId + "-" + suffix + ".scenario.json"
    def newPath = parent.resolve(baseName)
    try {
        Files.move(oldPath, newPath)
        conflict.rel = base.relativize(newPath).toString()
        conflict.renamedTo = conflict.rel
    } catch (Throwable ignored) {}
}
