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
 * C4 捣蛋鬼（确定性部分）：从 COBOL 错误处理代码提取"应被拒绝的输入"条件。
 * 不消耗 LLM 算力——纯正则匹配。
 */

static final List<String> REJECTION_PATTERNS = [
    "NOT\\s+=\\s+'?[A-Z0-9-]+'?",
    ">\\s+[A-Z0-9-]+",
    "<\\s+[A-Z0-9-]+",
    "=\\s+'FROZEN'",
    "=\\s+'CLOSED'",
    "=\\s+'INACTIVE'",
    ">=\\s+[A-Z0-9-]+",
    "<=?\\s+0\$"
]

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def maxCases = 5
    try { maxCases = Integer.parseInt((fn.str(fn.get("n_max_cases")) ?: "5").trim()) } catch (Throwable ignored) {}
    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
    def sourceRoot = base.resolve("source")

    def cases = []

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") && !it.toString().contains("negative") && !it.toString().contains("boundary") && !it.toString().contains("code-inferred") }
            .limit(15)
            .forEach { scenarioFile ->
                if (cases.size() >= maxCases) return
                try {
                    def sc = JSON.parseObject(Files.readString(scenarioFile, StandardCharsets.UTF_8))
                    def programId = sc.getString("moduleId") ?: sc.getString("programId") ?: "unknown"
                    def scenarioId = sc.getString("scenarioId") ?: scenarioFile.getFileName().toString()

                    // 从 COBOL 源码提取拒绝条件
                    def conditions = extractRejectionConditions(sourceRoot, programId)
                    if (conditions.isEmpty()) return

                    cases.add([
                        programId: programId,
                        scenarioId: scenarioId,
                        rejectionCondition: conditions[0],
                        allConditions: conditions,
                        originalScenario: sc
                    ])
                } catch (Throwable ignored) {}
            }
    }

    fn.put("n_negative_cases", JSON.toJSONString(cases))
    fn.put("n_negative_cases_count", cases.size())
    return agentResult(JSON.toJSONString([status: "COMPLETE", casesFound: cases.size()]))
}

static List<String> extractRejectionConditions(Path sourceRoot, String programId) {
    def conditions = []
    if (!Files.isDirectory(sourceRoot)) return conditions
    def found = Files.walk(sourceRoot)
        .filter { it.fileName.toString().toUpperCase().contains(programId.toUpperCase()) && it.toString().endsWith(".cbl") }
        .findFirst()
    if (!found.isPresent()) return conditions

    def lines = Files.readAllLines(found.get(), StandardCharsets.UTF_8)
    for (String line : lines) {
        def upper = line.toUpperCase().trim()
        if (!upper.startsWith("IF ") && !upper.startsWith("ELSE IF ")) continue
        for (String pattern : REJECTION_PATTERNS) {
            def matcher = (upper =~ pattern)
            if (matcher.find()) {
                conditions.add(upper.replaceAll(/^\s*(ELSE\s+)?IF\s+/, "").trim())
                break
            }
        }
        if (conditions.size() >= 8) break
    }
    return conditions.unique()
}
