import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * F3 追踪员：监控设计文档变更，标记受影响的 scenario。
 * 完全确定性——SHA256 哈希 + 文件对比。
 */

static final List<String> MONITORED_DOCS = [
    "design/architecture/target-architecture",
    "design/api/openapi.yaml",
    "requirement/discovery/screen/screen-meta-v1.json",
    "requirement/semantic/screen/screen-semantics-v1.json"
]

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectKey = (fn.str(fn.get("n_project_key")) ?: "").trim()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def version = (fn.str(fn.get("n_ir_version")) ?: "v1").trim()
    def action = (fn.str(fn.get("n_action")) ?: "check").trim()

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")
    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()

    def snapshotPath = base.resolve("test/equivalence/doc-sync-snapshot-${version}.json")
    def now = Instant.now()

    switch (action) {
        case "snapshot":
            return saveSnapshot(base, snapshotPath, version, now)
        case "check":
            return checkDrift(base, snapshotPath, version, now)
        default:
            return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"unknown action: ${action}"]))
    }
}

static String saveSnapshot(Path base, Path snapshotPath, String version, Instant now) {
    def docs = [:]
    for (String rel : MONITORED_DOCS) {
        def p = findFile(base, rel, version)
        if (p != null && Files.isRegularFile(p)) {
            def hash = sha256(p)
            docs[rel] = [path: base.relativize(p).toString(), sha256: hash, lastModified: Files.getLastModifiedTime(p).toString()]
        }
    }

    def snapshot = [schema:"cobol2java.doc-sync-snapshot", version:version,
                    generatedAt:now.toString(), docs:docs, docCount:docs.size()]
    Files.createDirectories(snapshotPath.getParent())
    Files.writeString(snapshotPath, JSON.toJSONString(snapshot, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)
    return agentResult(JSON.toJSONString([status:"COMPLETE", action:"snapshot", docCount:docs.size()]))
}

static String checkDrift(Path base, Path snapshotPath, String version, Instant now) {
    if (!Files.isRegularFile(snapshotPath)) {
        return agentResult(JSON.toJSONString([status:"SKIPPED", reason:"no snapshot found — run action=snapshot first"]))
    }

    def snapshot = JSON.parseObject(Files.readString(snapshotPath, StandardCharsets.UTF_8))
    def oldDocs = snapshot.getJSONObject("docs") ?: new JSONObject()

    def changes = []
    int changedCount = 0

    for (String rel : MONITORED_DOCS) {
        def p = findFile(base, rel, version)
        if (p == null || !Files.isRegularFile(p)) continue
        def newHash = sha256(p)
        def old = oldDocs.getJSONObject(rel)
        def oldHash = old?.getString("sha256") ?: ""

        if (!newHash.equals(oldHash)) {
            changedCount++
            changes.add([doc:rel, oldSha256:oldHash, newSha256:newHash, changedAt:now.toString()])
        }
    }

    def affectedScenarios = 0
    if (changedCount > 0) {
        // 标记所有 scenario 为可能受影响
        def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
        if (Files.isDirectory(scenarioDir)) {
            Files.walk(scenarioDir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
                .forEach { sp ->
                    try {
                        def sc = JSON.parseObject(Files.readString(sp, StandardCharsets.UTF_8))
                        def meta = sc.getJSONObject("goldenMetadata") ?: new JSONObject()
                        meta.put("staleDocDrift", true)
                        meta.put("staleDocDriftDetectedAt", now.toString())
                        sc.put("goldenMetadata", meta)
                        Files.writeString(sp, JSON.toJSONString(sc), StandardCharsets.UTF_8)
                        affectedScenarios++
                    } catch (Throwable ignored) {}
                }
        }
    }

    def report = [
        schema: "cobol2java.doc-sync-report",
        version: version,
        generatedAt: now.toString(),
        generatedBy: "agt_document_sync_monitor.c2j.validate",
        changedCount: changedCount,
        affectedScenarios: affectedScenarios,
        changes: changes,
        recommendation: changedCount > 0
            ? "建议运行 7.0 强制重译受影响的 ${affectedScenarios} 个 scenario" : "无需操作"
    ]

    def reportDir = base.resolve("test/equivalence")
    Files.createDirectories(reportDir)
    Files.writeString(reportDir.resolve("doc-sync-report-${version}.json"),
        JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)

    fn.put("n_doc_sync_changed", changedCount)
    fn.put("n_doc_sync_affected", affectedScenarios)
    return agentResult(JSON.toJSONString([status:"COMPLETE", changedDocs:changedCount, affectedScenarios:affectedScenarios]))
}

// ---- 辅助函数 ----

static Path findFile(Path base, String rel, String version) {
    // 支持带版本号的文件：target-architecture-v1.json
    def p = base.resolve(rel.replace("/", java.io.File.separator))
    if (Files.isRegularFile(p)) return p
    // 尝试追加版本号后缀
    def withVersion = rel + "-${version}.json"
    def pv = base.resolve(withVersion.replace("/", java.io.File.separator))
    if (Files.isRegularFile(pv)) return pv
    return null
}

static String sha256(Path file) {
    try {
        def digest = MessageDigest.getInstance("SHA-256")
        def bytes = Files.readAllBytes(file)
        def hash = digest.digest(bytes)
        return hash.encodeHex().toString()
    } catch (Throwable t) {
        return "error:${t.message}"
    }
}
