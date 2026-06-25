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
 * C1 侦察兵：确定性分析 COBOL 源码中的条件分支与 trace 覆盖对比。
 * 不消耗 LLM 算力——纯正则匹配 + 集合对比。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    def traceCatalogRel = (fn.str(fn.get("n_trace_catalog_rel")) ?: "verification/trace-scenarios/trace-catalog.json").trim()
    def sourceRootRel = (fn.str(fn.get("n_source_root_rel")) ?: "source").trim()

    if (!projectKey || !projectRoot) throw new IllegalStateException("Missing projectKey/projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def sourceRoot = base.resolve(sourceRootRel.replace("/", java.io.File.separator))

    // ===== 1. 读取 trace-catalog，建立已覆盖程序集合 =====
    def tracedPrograms = new LinkedHashSet()
    def tracedBranches = new LinkedHashSet()
    def catalogPath = base.resolve(traceCatalogRel.replace("/", java.io.File.separator))

    if (Files.isRegularFile(catalogPath)) {
        try {
            def catalog = JSON.parseObject(Files.readString(catalogPath, StandardCharsets.UTF_8))
            def modules = catalog.getJSONArray("modules") ?: new JSONArray()
            for (int i = 0; i < modules.size(); i++) {
                def m = modules.getJSONObject(i)
                if (m == null) continue
                def mid = m.getString("moduleId")
                def traceFile = m.getJSONObject("trace")?.getString("file")
                if (mid) {
                    // 从 moduleId 逆推程序名（通常 moduleId = 程序名去掉前导，简化处理）
                    tracedPrograms.add(mid)
                    if (traceFile) tracedBranches.add(traceFile)
                }
            }
        } catch (Throwable ignored) {}
    }

    // ===== 2. 扫描 COBOL 源文件，提取所有条件分支 =====
    def moduleAnalysis = []
    int totalBranches = 0
    int coveredBranches = 0
    int uncoveredBranches = 0

    if (Files.isDirectory(sourceRoot)) {
        Files.walk(sourceRoot)
            .filter { Files.isRegularFile(it) && it.toString().toLowerCase().endsWith(".cbl") }
            .forEach { cobolFile ->
                def programId = cobolFile.getFileName().toString()
                if (programId.contains(".")) programId = programId.substring(0, programId.lastIndexOf("."))
                programId = programId.toUpperCase()

                def relPath = base.relativize(cobolFile).toString()
                def lines = Files.readAllLines(cobolFile, StandardCharsets.UTF_8)
                def branches = []

                // 提取 IF 语句
                for (int li = 0; li < lines.size(); li++) {
                    def line = lines.get(li).trim()
                    def ifMatch = (line =~ /^\s*IF\s+(.+)/)
                    if (ifMatch.find()) {
                        def condition = ifMatch.group(1).trim()
                        // 简化：去掉 THEN/ELSE 关键字
                        condition = condition.replaceAll(/\s+THEN\s*$/, "").replaceAll(/\s+ELSE\s*$/, "")
                        def branchType = classifyBranch(condition)
                        def hasTrace = tracedPrograms.contains(programId)
                        branches.add([
                            branchId: "BR-${programId}-${li + 1}",
                            lineNumber: li + 1,
                            condition: condition,
                            branchType: branchType,
                            covered: hasTrace
                        ])
                        totalBranches++
                        if (hasTrace) coveredBranches++ else uncoveredBranches++
                    }

                    // 提取 EVALUATE 语句
                    def evalMatch = (line =~ /^\s*EVALUATE\s+(.+)/)
                    if (evalMatch.find()) {
                        def subject = evalMatch.group(1).trim()
                        branches.add([
                            branchId: "BR-${programId}-${li + 1}",
                            lineNumber: li + 1,
                            condition: "EVALUATE ${subject}",
                            branchType: "decision-table",
                            covered: tracedPrograms.contains(programId)
                        ])
                        totalBranches++
                        if (tracedPrograms.contains(programId)) coveredBranches++ else uncoveredBranches++
                    }
                }

                if (!branches.isEmpty()) {
                    def uncovered = branches.findAll { !it.covered }
                    moduleAnalysis.add([
                        moduleId: programId,
                        cobolSourceRel: relPath,
                        totalBranches: branches.size(),
                        coveredBranches: branches.size() - uncovered.size(),
                        uncoveredBranches: uncovered.size(),
                        branches: branches,
                        uncoveredBranchesDetail: uncovered,
                        recommendedActions: uncovered.collect { b ->
                            def action = b.branchType == "error-handling" ? "GENERATE_SCENARIO_FROM_CODE"
                                : b.branchType == "edge-case" ? "GENERATE_SCENARIO_FROM_CODE"
                                : "RECORD_TRACE"
                            [action: action, branchId: b.branchId, priority: "HIGH", note: "未覆盖分支: ${b.condition}"]
                        }
                    ])
                }
            }
    }

    // ===== 3. 写覆盖分析报告 =====
    def report = [
        schema: "cobol2java.coverage-analysis",
        version: "v1",
        projectKey: projectKey,
        analysisTime: Instant.now().toString(),
        generatedBy: "agt_coverage_analyzer.c2j.validate",
        summary: [
            totalCobolPrograms: moduleAnalysis.size(),
            programsWithTrace: tracedPrograms.size(),
            totalBranches: totalBranches,
            coveredBranches: coveredBranches,
            uncoveredBranches: uncoveredBranches,
            coverageRate: totalBranches > 0 ? String.format("%.1f%%", coveredBranches * 100.0 / totalBranches) : "0%"
        ],
        perModule: moduleAnalysis,
        recommendedActions: moduleAnalysis.collectMany { it.recommendedActions ?: [] }
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    def reportRel = "test/equivalence/coverage-analysis-v1.json"
    Files.writeString(base.resolve(reportRel), JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    // ===== 4. 返回 =====
    fn.put("n_coverage_total_branches", totalBranches)
    fn.put("n_coverage_covered_branches", coveredBranches)
    fn.put("n_coverage_uncovered_branches", uncoveredBranches)

    return agentResult(JSON.toJSONString([
        status: "COMPLETE",
        totalBranches: totalBranches,
        coveredBranches: coveredBranches,
        uncoveredBranches: uncoveredBranches,
        reportPath: reportRel
    ]))
}

static String classifyBranch(String condition) {
    def upper = condition.toUpperCase()
    if (upper.contains("ERROR") || upper.contains("ABEND") || upper.contains("INVALID")
        || upper.contains("NOT ") || upper.contains("SQLCODE")) return "error-handling"
    if (upper.contains("<") || upper.contains(">") || upper.contains("=") || upper.contains("NOT")) return "edge-case"
    if (upper.contains("STATUS") || upper.contains("FROZEN") || upper.contains("CLOSED")) return "business-rule"
    return "normal-path"
}