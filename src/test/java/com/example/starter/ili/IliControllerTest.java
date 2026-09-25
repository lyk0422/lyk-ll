package com.example.starter.ili;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * REST 入口测试：双回合导入、对齐、分级、复核、封板的 HTTP 语义与错误分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IliControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM ili_match_pair");
        jdbc.update("DELETE FROM ili_defect_reading");
        jdbc.update("DELETE FROM ili_batch");
    }

    private static final String SUBMIT_BODY = """
            {
              "requestId": "REQ-HTTP-1",
              "segmentId": "SEG-01",
              "runOldId": "RUN-2020",
              "runNewId": "RUN-2024",
              "runOldDate": "2020-01-01",
              "runNewDate": "2024-01-01",
              "oldDefects": [
                {"defectRef": "O-GROW", "absPositionM": 100.0, "depthPct": 10.0},
                {"defectRef": "O-SEVERE", "absPositionM": 200.0, "depthPct": 10.0},
                {"defectRef": "O-GONE", "absPositionM": 300.0, "depthPct": 40.0}
              ],
              "newDefects": [
                {"defectRef": "N-GROW", "absPositionM": 100.4, "depthPct": 30.0},
                {"defectRef": "N-SEVERE", "absPositionM": 200.3, "depthPct": 70.0},
                {"defectRef": "N-NEW", "absPositionM": 500.0, "depthPct": 25.0}
              ]
            }
            """;

    @Test
    void fullFlowOverHttp() throws Exception {
        // 提交并对齐分级。
        MvcResult submitted = mvc.perform(post("/api/ili/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALIGNED"))
                .andExpect(jsonPath("$.pairs", hasSize(2)))
                .andExpect(jsonPath("$.pairs[0].grade").value("SEVERE"))
                .andExpect(jsonPath("$.pairs[1].grade").value("GROWING"))
                .andExpect(jsonPath("$.newFindings", hasSize(1)))
                .andExpect(jsonPath("$.newFindings[0].defectRef").value("N-NEW"))
                .andExpect(jsonPath("$.disappeared", hasSize(1)))
                .andExpect(jsonPath("$.disappeared[0].defectRef").value("O-GONE"))
                .andReturn();
        String body = submitted.getResponse().getContentAsString();
        long batchId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.batchId")).longValue();
        long severePairId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.pairs[0].pairId")).longValue();

        // 同键同参重放：返回首次结果。
        mvc.perform(post("/api/ili/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId));

        // 同键异参：409。
        mvc.perform(post("/api/ili/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBMIT_BODY.replace("\"depthPct\": 25.0", "\"depthPct\": 26.0")))
                .andExpect(status().isConflict());

        // 复核严重缺陷：确认。
        mvc.perform(post("/api/ili/batches/{id}/reviews", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairId": %d, "decision": "CONFIRM", "reviewer": "zhangsan", "comment": "同意"}
                                """.formatted(severePairId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.finalGrade").value("SEVERE"));

        // 封板：幂等。
        mvc.perform(post("/api/ili/batches/{id}/seal", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
        mvc.perform(post("/api/ili/batches/{id}/seal", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        // 封板后迟到复核：409。
        mvc.perform(post("/api/ili/batches/{id}/reviews", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairId": %d, "decision": "DOWNGRADE", "reviewer": "lisi"}
                                """.formatted(severePairId)))
                .andExpect(status().isConflict());

        // 查询批次。
        mvc.perform(get("/api/ili/batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void validationAndNotFoundBranches() throws Exception {
        // 参数校验失败：400。
        mvc.perform(post("/api/ili/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"REQ-BAD\"}"))
                .andExpect(status().isBadRequest());

        // 批次不存在：404。
        mvc.perform(get("/api/ili/batches/{id}", 424242L))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/ili/batches/{id}/seal", 424242L))
                .andExpect(status().isNotFound());
    }
}
