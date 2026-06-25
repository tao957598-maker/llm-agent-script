import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.sql.Connection
import java.sql.DriverManager

/**
 * E3 副作用校验器：根据 scenario 中定义的 expectedSideEffects 检查数据库、日志等。
 * 确定性操作，通过 JDBC 和文件系统读取检查。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
    def results = []
    int totalChecked = 0
    int passed = 0
    int failed = 0

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
            .forEach { scenarioFile ->
                try {
                    def sc = JSON.parseObject(Files.readString(scenarioFile, StandardCharsets.UTF_8))
                    def expectedSideEffects = sc.getJSONObject("expectedSideEffects")
                    if (expectedSideEffects == null || expectedSideEffects.isEmpty()) return

                    totalChecked++
                    def scenarioId = sc.getString("scenarioId") ?: scenarioFile.getFileName().toString()
                    def checks = []

                    // 检查数据库副作用
                    def dbChecks = expectedSideEffects.getJSONObject("database")
                    if (dbChecks != null) {
                        def dbResult = checkDatabaseSideEffects(dbChecks)
                        checks.addAll(dbResult)
                    }

                    // 检查审计日志
                    def logChecks = expectedSideEffects.getJSONArray("auditLogs")
                    if (logChecks != null && !logChecks.isEmpty()) {
                        def logResult = checkAuditLogs(base, logChecks)
                        checks.addAll(logResult)
                    }

                    // 检查文件输出
                    def fileChecks = expectedSideEffects.getJSONArray("outputFiles")
                    if (fileChecks != null && !fileChecks.isEmpty()) {
                        def fileResult = checkOutputFiles(base, fileChecks)
                        checks.addAll(fileResult)
                    }

                    def allPassed = checks.every { it.passed }
                    if (allPassed) passed++ else failed++

                    results.add([
                        scenarioId: scenarioId,
                        passed: allPassed,
                        checks: checks
                    ])
                } catch (Throwable ignored) {}
            }
    }

    def report = [
        schema: "cobol2java.side-effect-check-report",
        version: "v1",
        generatedAt: Instant.now().toString(),
        generatedBy: "agt_side_effect_checker.c2j.validate",
        stats: [totalChecked: totalChecked, passed: passed, failed: failed],
        results: results
    ]
    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("side-effect-check-report-v1.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_side_effect_passed", passed)
    fn.put("n_side_effect_failed", failed)
    return agentResult(JSON.toJSONString([status:"COMPLETE", passed:passed, failed:failed]))
}

static List checkDatabaseSideEffects(JSONObject dbChecks) {
    def results = []
    def dbUrl = System.getenv("TEST_DB_URL") ?: "jdbc:h2:mem:testdb"
    def dbUser = System.getenv("TEST_DB_USER") ?: "sa"
    def dbPass = System.getenv("TEST_DB_PASS") ?: ""
    try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
        def tables = dbChecks.keySet()
        for (table in tables) {
            def expectedRows = dbChecks.getJSONArray(table)
            if (expectedRows != null) {
                for (int i = 0; i < expectedRows.size(); i++) {
                    def expected = expectedRows.getJSONObject(i)
                    def where = expected.getString("where") ?: "1=1"
                    def checkSql = "SELECT COUNT(*) FROM ${table} WHERE ${where}"
                    def stmt = conn.createStatement()
                    def rs = stmt.executeQuery(checkSql)
                    def count = rs.next() ? rs.getInt(1) : 0
                    rs.close(); stmt.close()
                    def expectedCount = expected.getInteger("count") ?: 1
                    def passed = count == expectedCount
                    results.add([table:table, where:where, expectedCount:expectedCount, actualCount:count, passed:passed])
                }
            }
        }
    } catch (Throwable t) {
        results.add([error:t.message, passed:false])
    }
    return results
}

static List checkAuditLogs(Path base, JSONArray logChecks) {
    def results = []
    def logPath = base.resolve("implementation/java/logs/audit.log")
    if (!Files.isRegularFile(logPath)) {
        results.add([check:"audit-log", passed:false, error:"audit.log not found"])
        return results
    }
    def content = Files.readString(logPath, StandardCharsets.UTF_8)
    for (int i = 0; i < logChecks.size(); i++) {
        def expected = logChecks.getString(i)
        def passed = content.contains(expected)
        results.add([check:"audit-log", expected:expected, passed:passed])
    }
    return results
}

static List checkOutputFiles(Path base, JSONArray fileChecks) {
    def results = []
    for (int i = 0; i < fileChecks.size(); i++) {
        def fc = fileChecks.getJSONObject(i)
        def filePath = fc.getString("path")
        def expectedContent = fc.getString("contains")
        def p = base.resolve(filePath.replace("/", java.io.File.separator))
        if (!Files.isRegularFile(p)) {
            results.add([file:filePath, passed:false, error:"file not found"])
            continue
        }
        def actual = Files.readString(p, StandardCharsets.UTF_8)
        def passed = actual.contains(expectedContent)
        results.add([file:filePath, expected:expectedContent, passed:passed])
    }
    return results
}
