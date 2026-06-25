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
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    double autoPass = 0.8
    double review = 0.5
    try { autoPass = Double.parseDouble((fn.str(fn.get("n_auto_pass_threshold")) ?: "0.8").trim()) } catch (Throwable ignored) {}
    try { review = Double.parseDouble((fn.str(fn.get("n_review_threshold")) ?: "0.5").trim()) } catch (Throwable ignored) {}

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
    def assessments = []
    int autoPassCount = 0, needsReviewCount = 0, needsRetranslationCount = 0

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
            .forEach { p ->
                def rel = base.relativize(p).toString()
                try {
                    def sc = JSON.parseObject(Files.readString(p, StandardCharsets.UTF_8))
                    def scenarioId = sc.getString("scenarioId") ?: p.getFileName().toString()

                    double llmConf = sc.getDoubleValue("confidence")
                    if (llmConf <= 0 || llmConf > 1) llmConf = 0.5

                    def goldenSource = sc.getJSONObject("goldenMetadata")?.getString("goldenSource") ?: "trace-screen"
                    double sourceRel = goldenSource == "manual-annotation" ? 0.95
                        : goldenSource == "shadow-run" ? 0.99
                        : goldenSource == "code-analysis" ? 0.8
                        : 0.6

                    def evidenceRefs = sc.getJSONArray("evidenceRefs")
                        ?: sc.getJSONObject("goldenMetadata")?.getJSONArray("evidenceRefs")
                        ?: new JSONArray()
                    double multiSource = evidenceRefs.isEmpty() ? 0.5 : Math.min(1.0, 0.6 + evidenceRefs.size() * 0.1)

                    double typeConsistency = checkTypeConsistency(sc)

                    def golden = sc.getJSONObject("goldenApiResponse")
                    def steps = sc.getJSONArray("steps") ?: new JSONArray()
                    int goldenFields = (golden != null && !golden.isEmpty()) ? golden.size() : 0
                    double completeness = goldenFields == 0
                        ? (steps.isEmpty() ? 0.5 : 0.2)
                        : Math.min(1.0, goldenFields / Math.max(2, steps.size()))

                    double score = Math.round(
                        (llmConf * 0.3 + sourceRel * 0.25 + multiSource * 0.2
                         + typeConsistency * 0.15 + completeness * 0.1) * 100) / 100.0

                    def verdict = score >= autoPass ? "AUTO_PASS"
                        : score >= review ? "NEEDS_REVIEW"
                        : "NEEDS_RETRANSLATION"

                    switch (verdict) {
                        case "AUTO_PASS": autoPassCount++; break
                        case "NEEDS_REVIEW": needsReviewCount++; break
                        case "NEEDS_RETRANSLATION": needsRetranslationCount++; break
                    }

                    def selfDoubts = []
                    if (evidenceRefs.isEmpty()) selfDoubts.add("无 evidenceRefs")
                    if (goldenSource == "trace-screen") selfDoubts.add("来源为 trace 推断")

                    assessments.add([
                        scenarioRel: rel, scenarioId: scenarioId, score: score, verdict: verdict,
                        goldenSource: goldenSource, llmConfidence: llmConf, sourceReliability: sourceRel,
                        multiSourceConsistency: multiSource, typeConsistency: typeConsistency,
                        completeness: completeness, selfDoubts: selfDoubts
                    ])

                    // 标记低分 scenario
                    if (verdict == "NEEDS_RETRANSLATION" || verdict == "NEEDS_REVIEW") {
                        def meta = sc.getJSONObject("goldenMetadata") ?: new JSONObject()
                        meta.put("trustScore", score)
                        meta.put("trustVerdict", verdict)
                        meta.put("trustAssessedAt", Instant.now().toString())
                        if (verdict == "NEEDS_RETRANSLATION") {
                            meta.put("needsRetranslation", true)
                            meta.put("retranslationReason", "可信度评分 ${score} < ${review}")
                        }
                        sc.put("goldenMetadata", meta)
                        Files.writeString(p, JSON.toJSONString(sc), StandardCharsets.UTF_8)
                    }
                } catch (Throwable ignored) {
                    assessments.add([scenarioRel: rel, score: 0.0, verdict: "NEEDS_RETRANSLATION", error: "parse-error"])
                    needsRetranslationCount++
                }
            }
    }

    def report = [
        schema: "cobol2java.golden-trust-assessment",
        version: "v1",
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_golden_trust_assessor.c2j.validate",
        autoPassThreshold: autoPass,
        reviewThreshold: review,
        stats: [totalAssessed: assessments.size(), autoPassCount: autoPassCount,
                needsReviewCount: needsReviewCount, needsRetranslationCount: needsRetranslationCount],
        assessments: assessments
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("golden-trust-assessment-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_golden_trust_assessor_total", assessments.size())
    fn.put("n_golden_trust_assessor_auto_pass", autoPassCount)
    fn.put("n_golden_trust_assessor_needs_review", needsReviewCount)
    fn.put("n_golden_trust_assessor_needs_retranslation", needsRetranslationCount)

    return agentResult(JSON.toJSONString([
        status: "COMPLETE", totalAssessed: assessments.size(),
        autoPass: autoPassCount, needsReview: needsReviewCount,
        needsRetranslation: needsRetranslationCount
    ]))
}

static double checkTypeConsistency(JSONObject sc) {
    def golden = sc.getJSONObject("goldenApiResponse")
    if (golden == null || golden.isEmpty()) return 0.5
    int total = 0, consistent = 0
    for (String key : golden.keySet()) {
        total++
        def val = golden.get(key)
        if (val instanceof Boolean || val instanceof Number) consistent++
        else if (val instanceof String) {
            if (val ==~ /^\d+(\.\d+)?$/) { /* 字符串数字，可能类型错 */ }
            else consistent++
        } else consistent++
    }
    return total == 0 ? 0.5 : (double) consistent / total
}
