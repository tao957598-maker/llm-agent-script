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
 * C3 边界值猎人（确定性部分）：从 COBOL PIC 定义生成边界值表。
 * 不消耗 LLM 算力——纯规则映射。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static final Map<String, List<String>> PIC_BOUNDARIES = [
    "9\\(5\\)": ["0", "1", "99999", "100000"],
    "9\\(7\\)": ["0", "1", "9999999", "10000000"],
    "9\\(5\\)V99": ["0.00", "0.01", "99999.99", "-1.00"],
    "9\\(7\\)V99": ["0.00", "0.01", "9999999.99", "-1.00"],
    "X\\(10\\)": ["", "A", "AAAAAAAAAA", "AAAAAAAAAAA", "!@#\$%"],
    "X\\(20\\)": ["", "AAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAA"],
    "A\\(20\\)": ["", "ABCDEFGHIJKLMNOPQRST"],
    "S9\\(4\\)": ["-9999", "0", "1", "9999"],
    "S9\\(7\\)V99": ["-9999999.99", "0.00", "0.01", "9999999.99"]
]

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def maxVariants = 8
    try { maxVariants = Integer.parseInt((fn.str(fn.get("n_max_variants")) ?: "8").trim()) } catch (Throwable ignored) {}
    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
    def sourceRoot = base.resolve("source")

    def tables = []

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") && !it.toString().contains("boundary") && !it.toString().contains("code-inferred") }
            .limit(20)
            .forEach { scenarioFile ->
                try {
                    def sc = JSON.parseObject(Files.readString(scenarioFile, StandardCharsets.UTF_8))
                    def programId = sc.getString("moduleId") ?: sc.getString("programId") ?: "unknown"
                    def scenarioId = sc.getString("scenarioId") ?: scenarioFile.getFileName().toString()

                    // 尝试找到对应 COBOL 源码并提取 PIC 定义
                    def picDefs = extractPicDefinitions(sourceRoot, programId)
                    if (picDefs.isEmpty()) return // 跳过，无法提取 PIC

                    def boundaryValues = []
                    picDefs.each { pic ->
                        def cleaned = pic.replaceAll(/\\s+/, "").toUpperCase()
                        PIC_BOUNDARIES.each { pattern, values ->
                            if (cleaned.find(pattern)) boundaryValues.addAll(values.take(3))
                        }
                    }
                    if (boundaryValues.isEmpty()) return

                    tables.add([
                        programId: programId,
                        scenarioId: scenarioId,
                        picDefinitions: picDefs,
                        boundaryTable: boundaryValues.unique().take(maxVariants),
                        originalScenario: sc
                    ])
                } catch (Throwable ignored) {}
            }
    }

    // 写边界值表
    def report = [schema: "cobol2java.boundary-value-tables", generatedAt: Instant.now().toString(),
                  tables: tables, totalTables: tables.size()]
    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("boundary-value-tables-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_boundary_tables", JSON.toJSONString(tables))
    fn.put("n_boundary_tables_count", tables.size())

    return agentResult(JSON.toJSONString([status: "COMPLETE", tablesGenerated: tables.size()]))
}

static List<String> extractPicDefinitions(Path sourceRoot, String programId) {
    def pics = []
    if (!Files.isDirectory(sourceRoot)) return pics
    def found = Files.walk(sourceRoot)
        .filter { it.fileName.toString().toUpperCase().contains(programId.toUpperCase()) && it.toString().endsWith(".cbl") }
        .findFirst()
    if (!found.isPresent()) return pics

    def lines = Files.readAllLines(found.get(), StandardCharsets.UTF_8)
    for (String line : lines) {
        def picMatch = (line =~ /PIC\s+(.+)/)
        if (picMatch.find()) {
            def picStr = picMatch.group(1).trim()
            // 简化：取第一个完整 PIC 子句
            pics.add(picStr.replaceAll(/\\..*/, ""))
        }
    }
    return pics.unique().take(6)
}
