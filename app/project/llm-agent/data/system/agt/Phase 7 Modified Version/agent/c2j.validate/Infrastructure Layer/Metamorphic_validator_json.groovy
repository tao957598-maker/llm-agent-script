import com.llm.agentcore.core.agent.FunctionUtil
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * E4 蜕变关系验证器（确定性部分）：构造扰动输入并调用新系统 API 收集数据。
 */

static String agentResult(Object value) {
    return JSON.toJSONString([type: "verifyResult", success: "true", result: value])
}

static Object execute(FunctionUtil fn) {
    def rootDir = com.llm.agent.common.constant.AppDirConsts.getRootDir()
    def projectRoot = (fn.str(fn.get("n_project_root")) ?: "").trim()
    if (!projectRoot) throw new IllegalStateException("Missing projectRoot")

    def base = Path.of(rootDir, projectRoot).toAbsolutePath().normalize()
    def scenarioDir = base.resolve("verification/trace-scenarios/by-module")
    def collectedData = new JSONArray()

    if (Files.isDirectory(scenarioDir)) {
        Files.walk(scenarioDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".scenario.json") }
            .limit(5)
            .forEach { scenarioFile ->
                try {
                    def sc = JSON.parseObject(Files.readString(scenarioFile, StandardCharsets.UTF_8))
                    def hints = sc.getJSONArray("apiMappingHints") ?: new JSONArray()
                    if (hints.isEmpty()) return
                    def hint = hints.getJSONObject(0)
                    def originalBody = hint.getJSONObject("body") ?: new JSONObject()

                    // 构造扰动输入
                    def perturbations = generatePerturbations(originalBody)
                    def responses = []
                    for (input in perturbations) {
                        def resp = callApi(hint, input)
                        if (resp != null) responses.add([input:input, output:resp])
                    }
                    if (!responses.isEmpty()) {
                        collectedData.add([
                            scenarioId: sc.getString("scenarioId"),
                            originalBody: originalBody,
                            responses: responses
                        ])
                    }
                } catch (Throwable ignored) {}
            }
    }

    fn.put("n_metamorphic_base_data", JSON.toJSONString(collectedData))
    return agentResult(JSON.toJSONString(collectedData))
}

static List generatePerturbations(JSONObject original) {
    def list = []
    // 对每个数值字段，增加和减少 10%
    for (String key : original.keySet()) {
        def val = original.get(key)
        if (val instanceof Number) {
            def v = val.doubleValue()
            def perturbedUp = new JSONObject(original)
            perturbedUp.put(key, v * 1.1)
            list.add(perturbedUp)
            def perturbedDown = new JSONObject(original)
            perturbedDown.put(key, Math.max(0, v * 0.9))
            list.add(perturbedDown)
        }
    }
    // 至少保留一个原始输入
    if (list.isEmpty()) list.add(original)
    return list.unique()
}

static JSONObject callApi(JSONObject hint, JSONObject body) {
    try {
        def path = hint.getString("path") ?: "/api/execute"
        def method = hint.getString("method") ?: "POST"
        def client = HttpClient.newHttpClient()
        def builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:8080" + path))
        if ("POST".equalsIgnoreCase(method)) {
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)))
        } else {
            builder.GET()
        }
        def resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() in [200, 400]) {
            return JSON.parseObject(resp.body())
        }
    } catch (Throwable t) {}
    return null
}
