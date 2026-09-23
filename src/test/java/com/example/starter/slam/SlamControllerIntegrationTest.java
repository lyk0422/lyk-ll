package com.example.starter.slam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 主流程端到端：写入关键帧/边、两次提交优化（第二次合并）、查询合并关系与结果。
 * 该用例开启真实 HTTP 序列化与 H2 事务边界，auto-dispatch 关闭时任务排队，手动 drain 后查询。
 */
class SlamControllerIntegrationTest extends AbstractSlamIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private com.example.starter.slam.service.OptimizationService optimizationService;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setupMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String covarianceJson() {
        List<Double> values = goodCovariance();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }

    private String keyframeJson(String id, String requestId, double x) {
        return """
                {"id":"%s","source":"lidar-a","version":0,"x":%f,"y":2.0,"theta":0.1,
                 "measurement":{"scanId":"%s"},"requestId":"%s"}
                """.formatted(id, x, id, requestId).replaceAll("\\s+", "");
    }

    private String edgeJson(String id, String from, String to, String requestId) {
        return """
                {"id":"%s","type":"ODOMETRY","source":"lidar-a","version":0,
                 "fromKeyframeId":"%s","toKeyframeId":"%s","dx":0.5,"dy":0.0,"dtheta":0.0,
                 "covariance":%s,"requestId":"%s"}
                """.formatted(id, from, to, covarianceJson(), requestId).replaceAll("\\s+", "");
    }

    @Test
    void endToEndWriteGraphSubmitTwiceAndQueryMergedResult() throws Exception {
        // 写入两个关键帧与一条里程计边
        mockMvc.perform(post("/api/slam/keyframes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyframeJson("kf1", "req-kf1", 1.0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.graphVersion").value(1))
                .andExpect(jsonPath("$.deduped").value(false));

        mockMvc.perform(post("/api/slam/keyframes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyframeJson("kf1", "req-kf1-dup", 1.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduped").value(true));

        mockMvc.perform(post("/api/slam/keyframes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyframeJson("kf2", "req-kf2", 1.5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.graphVersion").value(2));

        mockMvc.perform(post("/api/slam/edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edgeJson("e1", "kf1", "kf2", "req-e1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.graphVersion").value(3));

        // 同 ID 改参返回 409
        mockMvc.perform(post("/api/slam/edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edgeJson("e1", "kf1", "kf2", "req-e1-conflict")
                                .replace("0.5", "0.9")))
                .andExpect(status().isConflict());

        // 第一次提交优化：目标 v3
        MvcResult first = mockMvc.perform(post("/api/slam/optimizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"opt-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.targetGraphVersion").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long firstTaskId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        // 第二次提交优化：无新写入时也被合并进第一次的任务
        MvcResult second = mockMvc.perform(post("/api/slam/optimizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"opt-2\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedIntoId").value(firstTaskId))
                .andReturn();
        long secondTaskId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asLong();

        // 执行队列
        optimizationService.drainQueue();

        // 按 requestId 查询合并关系
        mockMvc.perform(get("/api/slam/requests/opt-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedIntoId").value(firstTaskId));

        // 被合并请求查询到保留任务产出的同一结果
        JsonNode resultViaSecond = objectMapper.readTree(
                mockMvc.perform(get("/api/slam/tasks/" + secondTaskId + "/result"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.graphVersion").value(3))
                        .andReturn().getResponse().getContentAsString());
        JsonNode resultViaFirst = objectMapper.readTree(
                mockMvc.perform(get("/api/slam/tasks/" + firstTaskId + "/result"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(resultViaSecond.get("id").asLong()).isEqualTo(resultViaFirst.get("id").asLong());

        // 保留任务成功，且只执行了一次优化
        mockMvc.perform(get("/api/slam/tasks/" + firstTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(optimizer.getCallCount()).isEqualTo(1);
    }

    @Test
    void rejectedLoopIsAuditedViaHttp() throws Exception {
        mockMvc.perform(post("/api/slam/keyframes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyframeJson("kf1", "req-kf1", 1.0)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/slam/keyframes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyframeJson("kf2", "req-kf2", 1.5)))
                .andExpect(status().isCreated());

        String badLoop = """
                {"id":"loop-bad","type":"LOOP","source":"lidar-a","version":0,
                 "fromKeyframeId":"kf1","toKeyframeId":"kf2","dx":0.5,"dy":0.0,"dtheta":0.0,
                 "covariance":%s,"score":0.5,"residual":0.1,"requestId":"req-loop-bad"}
                """.formatted(covarianceJson()).replaceAll("\\s+", "");
        // 不可信回环正常 201 入库但状态 REJECTED（审计语义，非 HTTP 错误）
        mockMvc.perform(post("/api/slam/edges")
                        .contentType(MediaType.APPLICATION_JSON).content(badLoop))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("score")))
                .andExpect(jsonPath("$.graphVersion").doesNotExist());
    }
}
