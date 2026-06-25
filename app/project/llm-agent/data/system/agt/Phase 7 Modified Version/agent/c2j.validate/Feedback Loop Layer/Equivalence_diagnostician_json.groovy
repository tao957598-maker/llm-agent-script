import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * D1 急诊医生：确定性分类所有测试失败用例。
 * 纯正则匹配 + XML 解析，不消耗 LLM 算力。
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
    def surefireDir = base.resolve("implementation/java/target/surefire-reports")

    if (!Files.isDirectory(surefireDir)) {
        fn.put("n_diagnostician_total_failures", 0)
        fn.put("n_diagnostician_golden_mismatch", 0)
        fn.put("n_diagnostician_code_bug", 0)
        return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"no surefire reports directory"]))
    }

    def xmlFiles = Files.list(surefireDir)
        .filter { it.toString().endsWith(".xml") }
        .collect { it }

    if (xmlFiles.isEmpty()) {
        fn.put("n_diagnostician_total_failures", 0)
        return agentResult(JSON.toJSONString([status:"PASS", totalFailures:0, summary:"all tests passed"]))
    }

    def results = []
    int totalFailures = 0
    int goldenMismatch = 0
    int codeBug = 0
    int apiNotFound = 0
    int mapGap = 0
    int envError = 0
    int unknown = 0

    xmlFiles.each { xmlFile ->
        try {
            def factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            def builder = factory.newDocumentBuilder()
            def doc = builder.parse(xmlFile.toFile())
            doc.getDocumentElement().normalize()

            def testCases = doc.getElementsByTagName("testcase")
            for (int i = 0; i < testCases.getLength(); i++) {
                def tc = testCases.item(i)
                def className = tc.getAttributes().getNamedItem("classname")?.getNodeValue() ?: "unknown"
                def testName = tc.getAttributes().getNamedItem("name")?.getNodeValue() ?: "unknown"
                def timeStr = tc.getAttributes().getNamedItem("time")?.getNodeValue() ?: "0"

                def children = tc.getChildNodes()
                String failureText = ""
                boolean isError = false

                for (int j = 0; j < children.getLength(); j++) {
                    def node = children.item(j)
                    if (node.getNodeName() == "failure") {
                        failureText = node.getTextContent() ?: ""
                        break
                    }
                    if (node.getNodeName() == "error") {
                        failureText = node.getTextContent() ?: ""
                        isError = true
                        break
                    }
                }

                if (failureText.isEmpty()) continue
                totalFailures++

                def category = classify(testName, failureText, isError)
                switch (category) {
                    case "GOLDEN_MISMATCH": goldenMismatch++; break
                    case "CODE_BUG": codeBug++; break
                    case "API_NOT_FOUND": apiNotFound++; break
                    case "MAP_GAP": mapGap++; break
                    case "ENV_ERROR": envError++; break
                    default: unknown++; break
                }

                results.add([
                    className: className,
                    testName: testName,
                    category: category,
                    isError: isError,
                    timeSeconds: timeStr,
                    excerpt: failureText.length() > 500 ? failureText.substring(0, 500) + "..." : failureText
                ])
            }
        } catch (Throwable t) {
            results.add([file: xmlFile.toString(), category: "PARSE_ERROR", excerpt: t.message?.take(500)])
            totalFailures++; envError++
        }
    }

    def byCategory = [
        GOLDEN_MISMATCH: goldenMismatch, CODE_BUG: codeBug,
        API_NOT_FOUND: apiNotFound, MAP_GAP: mapGap,
        ENV_ERROR: envError, UNKNOWN: unknown
    ]

    def report = [
        schema: "cobol2java.failure-analysis",
        version: version, projectKey: projectKey,
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_equivalence_diagnostician.c2j.validate",
        totalFailures: totalFailures,
        byCategory: byCategory,
        failures: results,
        summary: buildSummary(totalFailures, goldenMismatch, codeBug, apiNotFound, mapGap, envError)
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    def reportRel = "test/equivalence/failure-analysis-${version}.json"
    Files.writeString(base.resolve(reportRel),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    // 写 Markdown 摘要
    def md = new StringBuilder()
    md.append("# Phase 7 失败诊断报告\n\n")
    md.append("- 总失败数: **${totalFailures}**\n")
    md.append("- GOLDEN_MISMATCH（golden 值可能不正确）: **${goldenMismatch}**\n")
    md.append("- CODE_BUG（Java 代码有 bug）: **${codeBug}**\n")
    md.append("- API_NOT_FOUND（API 映射可能错误）: **${apiNotFound}**\n")
    md.append("- MAP_GAP（场景未映射）: **${mapGap}**\n")
    md.append("- ENV_ERROR（环境问题）: **${envError}**\n\n")
    md.append("## 建议动作\n\n")
    if (goldenMismatch > 0) md.append("- GOLDEN_MISMATCH → 运行 D2 指挥官自动标记重译\n")
    if (codeBug > 0) md.append("- CODE_BUG → 通知 Phase 6 repair Agent\n")
    if (apiNotFound > 0) md.append("- API_NOT_FOUND → 运行 B4 活地图重新解析映射\n")
    if (mapGap > 0) md.append("- MAP_GAP → 确认是否需要映射或登记 waiver\n")
    if (envError > 0) md.append("- ENV_ERROR → 检查测试环境\n")
    Files.writeString(reportDir.resolve("failure-analysis-${version}.md"), md.toString(), StandardCharsets.UTF_8)

    fn.put("n_diagnostician_total_failures", totalFailures)
    fn.put("n_diagnostician_golden_mismatch", goldenMismatch)
    fn.put("n_diagnostician_code_bug", codeBug)
    fn.put("n_diagnostician_api_not_found", apiNotFound)
    fn.put("n_diagnostician_map_gap", mapGap)
    fn.put("n_diagnostician_env_error", envError)

    return agentResult(JSON.toJSONString([
        status: totalFailures == 0 ? "PASS" : "FAIL",
        totalFailures: totalFailures,
        byCategory: byCategory,
        reportPath: reportRel
    ]))
}

static String classify(String testName, String failureText, boolean isError) {
    if (testName == null) testName = ""
    if (failureText == null) failureText = ""

    if (testName.contains("mappingGap") || testName.contains("_noCapability")
        || failureText.contains("TRACE_UNMAPPED") || failureText.contains("mapping-gap"))
        return "MAP_GAP"

    if (failureText.contains("404") || failureText.contains("Not Found"))
        return "API_NOT_FOUND"

    if (failureText.contains("Connection refused") || failureText.contains("ConnectException")
        || failureText.contains("timeout") || failureText.contains("Timed out")
        || failureText.contains("SocketException"))
        return "ENV_ERROR"

    if (failureText.contains("NullPointerException") || failureText.contains("SQLException")
        || failureText.contains("ClassCastException") || failureText.contains("ArrayIndexOutOfBoundsException")
        || failureText.contains("NoSuchElementException") || failureText.contains("IllegalArgumentException")
        || failureText.contains("IllegalStateException"))
        return "CODE_BUG"

    if (failureText.contains("expected:") || failureText.contains("but was:")
        || failureText.contains("==> expected:"))
        return "GOLDEN_MISMATCH"

    return isError ? "CODE_BUG" : "UNKNOWN"
}

static String buildSummary(int total, int golden, int code, int api, int gap, int env) {
    if (total == 0) return "所有测试通过。"
    def parts = []
    if (golden > 0) parts.add("${golden} 个 GOLDEN_MISMATCH")
    if (code > 0) parts.add("${code} 个 CODE_BUG")
    if (api > 0) parts.add("${api} 个 API_NOT_FOUND")
    if (gap > 0) parts.add("${gap} 个 MAP_GAP")
    if (env > 0) parts.add("${env} 个 ENV_ERROR")
    return "共 ${total} 个失败用例。${parts.join("；")}。"
}
