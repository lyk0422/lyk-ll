package com.example.starter.orchestrator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 编排 REST API 测试：验证 HTTP 状态码语义（201/200 幂等、409 冲突、400 循环、404 不存在）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_orchestrator_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PlanControllerTest {

    private static final String PLAN_JSON = """
            {
              "planId": "web-p1",
              "version": 1,
              "requestId": "web-r1",
              "nodes": [
                {"key": "a", "healthCheck": "PASS", "dependsOn": []},
                {"key": "b", "healthCheck": "PASS", "dependsOn": ["a"], "compensation": "stopB"}
              ]
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM launch_event");
        jdbc.update("DELETE FROM launch_node");
        jdbc.update("DELETE FROM launch_plan");
    }

    @AfterAll
    static void shutdown(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void submitReturns201ThenIdempotentReplayReturns200() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));
    }

    @Test
    void sameRequestIdWithDifferentPayloadReturns409() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isCreated());

        String changed = PLAN_JSON.replace("\"PASS\", \"dependsOn\": []", "\"FAIL\", \"dependsOn\": []");
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    @Test
    void cyclicPlanReturns400WithCyclePath() throws Exception {
        String cyclic = """
                {
                  "planId": "web-p2",
                  "version": 1,
                  "requestId": "web-r2",
                  "nodes": [
                    {"key": "a", "healthCheck": "PASS", "dependsOn": ["b"]},
                    {"key": "b", "healthCheck": "PASS", "dependsOn": ["a"]}
                  ]
                }
                """;
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(cyclic))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.cyclePath").isArray())
                .andExpect(jsonPath("$.cyclePath[0]").value("a"))
                .andExpect(jsonPath("$.cyclePath[2]").value("a"));
    }

    @Test
    void unknownPlanReturns404() throws Exception {
        mvc.perform(get("/api/plans/missing")).andExpect(status().isNotFound());
        mvc.perform(post("/api/plans/missing/execute")).andExpect(status().isNotFound());
        mvc.perform(post("/api/plans/missing/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void executeThenCancelOnFinishedPlanReturns409() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/plans/web-p1/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(post("/api/plans/web-p1/cancel"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/plans/web-p1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'PLAN_SUCCEEDED')]").exists());
    }
}
