package com.example.starter.orchestrator.api;

import com.example.starter.orchestrator.SyncExecutorTestConfig;
import com.example.starter.orchestrator.repo.OrchestratorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 语义测试：提交、查询、取消、恢复入口及 400/404/409/422 错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SyncExecutorTestConfig.class)
class LaunchPlanControllerH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrchestratorRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAllData();
    }

    private static final String VALID_PLAN = """
            {
              "planId": "p-http", "version": "v1", "requestId": "req-http",
              "planName": "http-plan",
              "nodes": [
                {"nodeId": "db", "nodeName": "db",
                 "healthCheck": {"fail": false, "message": "ok"},
                 "compensation": {"action": "drop-db", "simulateFail": false}},
                {"nodeId": "app", "dependsOn": ["db"]}
              ]
            }
            """;

    @Test
    void submitQueryAndResumeReturn200() throws Exception {
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.planId").value("p-http"))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.requestId").value("req-http"))
                .andExpect(jsonPath("$.nodes[0].state").value("SUCCESS"));

        mockMvc.perform(get("/api/launch/plans/p-http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(post("/api/launch/plans/p-http/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void idempotentResubmitSamePayloadReturns200() throws Exception {
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isOk());
        // 同 requestId 同参：幂等
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void differentPayloadSameRequestIdReturns409() throws Exception {
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isOk());

        String changed = VALID_PLAN.replace("\"nodeId\": \"app\"",
                "\"nodeId\": \"app\", \"launchDelayMs\": 30");
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void cyclicPlanReturns422WithFullPath() throws Exception {
        String cyclic = """
                {
                  "planId": "p-http-cycle", "version": "v1", "requestId": "req-cycle",
                  "nodes": [
                    {"nodeId": "a", "dependsOn": ["b"]},
                    {"nodeId": "b", "dependsOn": ["c"]},
                    {"nodeId": "c", "dependsOn": ["a"]}
                  ]
                }
                """;
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(cyclic))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLIC_DEPENDENCY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("a -> b -> c -> a")));
    }

    @Test
    void invalidReferenceReturns400() throws Exception {
        String bad = """
                {
                  "planId": "p-http-bad", "version": "v1", "requestId": "req-bad",
                  "nodes": [
                    {"nodeId": "a", "dependsOn": ["ghost"]}
                  ]
                }
                """;
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        String noNodes = """
                {"planId": "p", "version": "v1", "requestId": "r", "nodes": []}
                """;
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(noNodes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownPlanGetAndCancelReturn404() throws Exception {
        mockMvc.perform(get("/api/launch/plans/p-ghost"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/launch/plans/p-ghost/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"c1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelCompletedPlanIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/launch/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/launch/plans/p-http/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"c-late\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
