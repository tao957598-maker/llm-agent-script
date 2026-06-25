import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * E5 不变式提取器（确定性部分）：从 COBOL 源码中提取数值约束。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    def maxPrograms = 10
    try { maxPrograms = Integer.parseInt((fn.str(fn.get("n_max_programs")) ?: "10").trim()) } catch (Throwable ignored) {}

    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def sourceRoot = base.resolve("source")
    def invariants = new JSONArray()

    if (Files.isDirectory(sourceRoot)) {
        Files.walk(sourceRoot)
            .filter { Files.isRegularFile(it) && it.toString().toLowerCase().endsWith(".cbl") }
            .limit(maxPrograms)
            .forEach { cobolFile ->
                def programId = cobolFile.getFileName().toString()
                if (programId.contains(".")) programId = programId.substring(0, programId.lastIndexOf("."))
                programId = programId.toUpperCase()

                def lines = Files.readAllLines(cobolFile, StandardCharsets.UTF_8)
                for (int li = 0; li < lines.size(); li++) {
                    def line = lines.get(li).trim().toUpperCase()
                    // 提取 COMPUTE 语句中的算式，判断是否为守恒关系
                    def computeMatch = (line =~ /COMPUTE\s+(\S+)\s*=\s*(.+)/)
                    if (computeMatch.find()) {
                        def target = computeMatch.group(1).trim()
                        def expr = computeMatch.group(2).trim()
                        // 简单的守恒检测：A = B + C → 总和不变
                        if (expr =~ /\w+\s*\+\s*\w+/) {
                            invariants.add([
                                type: "arithmetic",
                                description: "${target} = ${expr} (可能隐含守恒约束)",
                                source: cobolFile.getFileName().toString(),
                                line: li + 1,
                                programId: programId
                            ])
                        }
                    }
                    // 提取条件中的比较，如 BALANCE >= 0
                    def ifMatch = (line =~ /IF\s+(\S+)\s*(>=|<=|>|<|=)\s*(\S+)/)
                    if (ifMatch.find()) {
                        def var = ifMatch.group(1).trim()
                        def op = ifMatch.group(2).trim()
                        def val = ifMatch.group(3).trim()
                        if (val.isNumber() && op in [">=", "<="]) {
                            invariants.add([
                                type: "boundary",
                                description: "${var} ${op} ${val}",
                                source: cobolFile.getFileName().toString(),
                                line: li + 1,
                                programId: programId
                            ])
                        }
                    }
                }
            }
    }

    def result = new JSONObject()
    result.put("invariants", invariants)
    result.put("count", invariants.size())

    fn.put("n_invariant_candidates_json", JSON.toJSONString(result))
    return agentResult(JSON.toJSONStr
