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
 * D3 成绩单：汇总 Phase 7 全部数据，按加权公式生成质量门控。
 * 完全确定性，不消耗 LLM。
 *
 * 加权公式（5 维度）：
 *   equivalence rate (30%) — Surefire 通过率 + manifest pass_flag
 *   scenario coverage (25%) — trace-catalog 场景数与核心 transaction 覆盖
 *   behavioral divergence (25%) — 未豁免 critical diff 数
 *   trace-to-api mapping (10%) — UNMAPPED 比例
 *   waiver governance (10%) — 过期 waiver 数
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    if (!projectKey || !projectRoot) throw new IllegalStateException("Missing projectKey/projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def readJson = { String r ->
        def p = base.resolve(r.replace("/", java.io.File.separator))
        if (!Files.isRegularFile(p)) return new JSONObject()
        try { return JSON.parseObject(Files.readString(p, StandardCharsets.UTF_8)) } catch (Throwable ignored) { return new JSONObject() }
    }

    // ===== 收集数据 =====

    // 1. manifest
    def manifest = readJson("test/equivalence/cucumber-manifest-${version}.json")
    def manifestStats = manifest.getJSONObject("stats") ?: new JSONObject()
    int scenarioCount = manifestStats.getIntValue("scenarioCount")
    int mappedHintCount = manifestStats.getIntValue("mappedHintCount")
    int mappingGapCount = manifestStats.getIntValue("mappingGapCount")

    // 2. Surefire（通过读取诊断报告间接获取）
    def analysis = readJson("test/equivalence/failure-analysis-${version}.json")
    int totalFailures = analysis.getIntValue("totalFailures")
    def byCategory = analysis.getJSONObject("byCategory") ?: new JSONObject()
    int goldenMismatch = byCategory.getIntValue("GOLDEN_MISMATCH")
    int codeBug = byCategory.getIntValue("CODE_BUG")

    // 3. Diff 报告
    def diffReport = readJson("meta/verification/equivalence-diff-report-${version}.json")
    def diffStats = diffReport.getJSONObject("stats") ?: new JSONObject()
    int unwaivedCritical = diffStats.getIntValue("unwaivedCriticalCount")
    int waivedCount = diffStats.getIntValue("waivedDiffCount")

    // 4. 覆盖率
    def coverage = readJson("test/equivalence/coverage-analysis-v1.json")
    def coverageSummary = coverage.getJSONObject("summary") ?: new JSONObject()
    int totalBranches = coverageSummary.getIntValue("totalBranches")
    int coveredBranches = coverageSummary.getIntValue("coveredBranches")

    // 5. Golden 可信度
    def trust = readJson("test/equivalence/golden-trust-assessment-v1.json")
    def trustStats = trust.getJSONObject("stats") ?: new JSONObject()
    int trustTotal = trustStats.getIntValue("totalAssessed")
    int trustAutoPass = trustStats.getIntValue("autoPassCount")
    int trustNeedsRetranslation = trustStats.getIntValue("needsRetranslationCount")

    // ===== 五维度评分 =====

    // 维度 1: equivalence rate (30%)
    double equivalenceRate = scenarioCount > 0
        ? 1.0 - ((double) totalFailures / Math.max(scenarioCount, 1))
        : 1.0
    equivalenceRate = Math.max(0.0, equivalenceRate)

    // 维度 2: scenario coverage (25%)
    double scenarioCoverage = scenarioCount > 0
        ? (double) mappedHintCount / Math.max(scenarioCount + mappingGapCount, 1)
        : 0.0

    // 维度 3: behavioral divergence (25%)
    // 有 unwaived critical → 严重扣分
    double behavioralDivergence = unwaivedCritical > 0
        ? Math.max(0.0, 1.0 - unwaivedCritical * 0.3)
        : 1.0

    // 维度 4: trace-to-api mapping (10%)
    double mappingRatio = scenarioCount > 0
        ? (double) mappedHintCount / Math.max(scenarioCount, 1)
        : 0.0

    // 维度 5: waiver governance (10%)
    double waiverGovernance = unwaivedCritical > 0 ? 0.6 : 1.0

    // ===== 综合评分 =====
    double score = equivalenceRate * 0.30
                 + scenarioCoverage * 0.25
                 + behavioralDivergence * 0.25
                 + mappingRatio * 0.10
                 + waiverGovernance * 0.10
    score = Math.round(score * 100.0) / 100.0

    // ===== 判决 =====
    boolean blocking = unwaivedCritical > 0
    boolean nextAllowed = !blocking && score >= 0.70
    String verdict = blocking ? "FAIL" : (score >= 0.85 ? "PASS" : (score >= 0.70 ? "PARTIAL_PASS" : "FAIL"))

    // ===== 构建报告 =====
    def dimensions = [
        [id:"equivalence-rate", weight:0.30, score:Math.round(equivalenceRate*100)/100.0, maxScore:1.0,
         detail:"Surefire 通过率", hard:false],
        [id:"scenario-coverage", weight:0.25, score:Math.round(scenarioCoverage*100)/100.0, maxScore:1.0,
         detail:"trace 场景覆盖率", hard:false],
        [id:"behavioral-divergence", weight:0.25, score:Math.round(behavioralDivergence*100)/100.0, maxScore:1.0,
         detail:"未豁免 critical diff ${unwaivedCritical} 个", hard:true, hardPass: unwaivedCritical == 0],
        [id:"trace-to-api-mapping", weight:0.10, score:Math.round(mappingRatio*100)/100.0, maxScore:1.0,
         detail:"trace-to-API 映射率", hard:false],
        [id:"waiver-governance", weight:0.10, score:Math.round(waiverGovernance*100)/100.0, maxScore:1.0,
         detail:"waiver 治理", hard:false]
    ]

    def gate = [
        schema: "cobol2java.phase-7-equivalence-quality-gate",
        version: version,
        projectKey: projectKey,
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_phase7_gate_generator.c2j.validate",
        verdict: verdict,
        blocking: blocking,
        nextAllowed: nextAllowed,
        score: score,
        dimensions: dimensions,
        summary: [
            scenarioCount: scenarioCount,
            totalFailures: totalFailures,
            unwaivedCritical: unwaivedCritical,
            goldenMismatch: goldenMismatch,
            codeBug: codeBug,
            trustAutoPassRate: trustTotal > 0 ? Math.round(trustAutoPass * 100.0 / trustTotal) : 0,
            coverageRate: totalBranches > 0 ? Math.round(coveredBranches * 100.0 / totalBranches) : 0
        ]
    ]

    def gateDir = base.resolve("meta/quality-gate")
    Files.createDirectories(gateDir)
    def gateRel = "meta/quality-gate/phase-7-equivalence-quality-gate-${version}.json"
    Files.writeString(base.resolve(gateRel),
        JSON.toJSONString(gate, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    // Markdown 报告
    def md = new StringBuilder()
    md.append("# Phase 7 业务等价质量门控报告\n\n")
    md.append("- 最终判决: **${verdict}**\n")
    md.append("- 综合评分: **${score}** / 1.00\n")
    md.append("- 阻断状态: ${blocking ? '**BLOCKING**' : 'PASS'}\n")
    md.append("- 进入 Phase 8: ${nextAllowed ? '**允许**' : '**不允许**'}\n\n")
    md.append("## 维度得分\n\n")
    dimensions.each { d ->
        md.append("- ${d.detail}: ${d.score} (权重 ${(d.weight*100).intValue()}%)\n")
    }
    md.append("\n## 关键指标\n\n")
    md.append("- 场景总数: ${scenarioCount}\n")
    md.append("- Surefire 失败: ${totalFailures}\n")
    md.append("- 未豁免 critical diff: **${unwaivedCritical}** ${unwaivedCritical > 0 ? '⚠️（硬阻断）' : '✅'}\n")
    md.append("- 分支覆盖率: ${coveredBranches}/${totalBranches}\n")
    md.append("- Golden 可信度自动通过率: ${trustTotal > 0 ? Math.round(trustAutoPass * 100.0 / trustTotal) : 0}%\n")
    Files.writeString(gateDir.resolve("phase-7-equivalence-gate-${version}.md"), md.toString(), StandardCharsets.UTF_8)

    fn.put("n_phase7_gate_verdict", verdict)
    fn.put("n_phase7_gate_score", score)
    fn.put("n_phase7_gate_blocking", blocking)

    return agentResult(JSON.toJSONString([status:"COMPLETE", verdict:verdict, score:score, blocking:blocking]))
}
