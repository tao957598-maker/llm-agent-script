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
 * E2 测试数据管理器：管理测试数据的快照、时间对齐、隔离与清理。
 * 确定性操作，所有数据库操作通过 JDBC。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def action = (fn.str(fn.get("n_action")) ?: "setup").trim()
    def snapshotRel = (fn.str(fn.get("n_data_snapshot_rel")) ?: "").trim()

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")
    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()

    def dbUrl = System.getenv("TEST_DB_URL") ?: "jdbc:h2:mem:testdb"
    def dbUser = System.getenv("TEST_DB_USER") ?: "sa"
    def dbPass = System.getenv("TEST_DB_PASS") ?: ""

    try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
        switch (action) {
            case "setup":
                setupTestData(conn, base, snapshotRel)
                injectVirtualTime(base)
                break
            case "cleanup":
                cleanupTestData(conn)
                break
            default:
                return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"unknown action: ${action}"]))
        }
    } catch (Throwable t) {
        return agentResult(JSON.toJSONString([status:"FAILED", error:t.message]))
    }

    return agentResult(JSON.toJSONString([status:"COMPLETE", action:action]))
}

static void setupTestData(Connection conn, Path base, String snapshotRel) {
    // 如果提供了快照文件，导入快照；否则仅执行基础初始化
    if (!snapshotRel.isEmpty()) {
        def snapshotPath = base.resolve(snapshotRel.replace("/", java.io.File.separator))
        if (Files.isRegularFile(snapshotPath)) {
            def snapshot = JSON.parseObject(Files.readString(snapshotPath, StandardCharsets.UTF_8))
            def inserts = snapshot.getJSONArray("inserts") ?: new JSONArray()
            for (int i = 0; i < inserts.size(); i++) {
                def sql = inserts.getString(i)
                if (sql != null && !sql.isBlank()) {
                    def stmt = conn.createStatement()
                    stmt.execute(sql)
                    stmt.close()
                }
            }
        }
    }
    // 为每个测试场景分配独立账号（示例：确保常用账号存在）
    def stmt = conn.createStatement()
    stmt.execute("CREATE TABLE IF NOT EXISTS ACCOUNT (ID VARCHAR(10), BALANCE DECIMAL(10,2))")
    stmt.execute("MERGE INTO ACCOUNT (ID, BALANCE) VALUES ('B0001', 1000.00)")
    stmt.execute("MERGE INTO ACCOUNT (ID, BALANCE) VALUES ('B0002', 200.00)")
    stmt.close()
}

static void injectVirtualTime(Path base) {
    // 将虚拟时间写入配置文件，供测试时读取
    def timeFile = base.resolve("test/equivalence/virtual-time.txt")
    Files.createDirectories(timeFile.getParent())
    // 默认使用 trace 录制时间，若无则用当前时间
    Files.writeString(timeFile, Instant.now().toString(), StandardCharsets.UTF_8)
}

static void cleanupTestData(Connection conn) {
    // 清理测试数据
    def stmt = conn.createStatement()
    stmt.execute("DELETE FROM ACCOUNT WHERE ID IN ('B0001', 'B0002')")
    stmt.close()
}
