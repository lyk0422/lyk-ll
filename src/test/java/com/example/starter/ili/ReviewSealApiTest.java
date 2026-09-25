package com.example.starter.ili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 复核与封板集成测试（H2 内存库 + 固定时钟）：确认/降级、封板前置条件、
 * 封板终态幂等、封板后修改与迟到复核拒绝。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ili_test_review;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewSealApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SegmentService segmentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM match_pair");
        jdbc.update("DELETE FROM defect_reading");
        jdbc.update("DELETE FROM alignment_batch");
        jdbc.update("DELETE FROM inspection_run");
        jdbc.update("DELETE FROM pipeline_weld");
        jdbc.update("DELETE FROM pipeline_segment");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    /** 创建管段并导入标准样本，返回批次ID及 grade -> pairId 映射。 */
    private Map<String, Long> importSampleBatch(String requestId) throws Exception {
        segmentService.createSegment(TestData.segmentRequest());
        MvcResult result = mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.sampleImport(requestId))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Map<String, Long> ids = new HashMap<>();
        ids.put("batchId", body.get("batchId").asLong());
        for (JsonNode pair : body.get("pairs")) {
            ids.put(pair.get("grade").asText(), pair.get("pairId").asLong());
        }
        return ids;
    }

    private void review(long batchId, long pairId, String decision) throws Exception {
        review(batchId, pairId, decision, status().isOk());
    }

    private MvcResult review(long batchId, long pairId, String decision,
            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mockMvc.perform(post("/api/alignment-batches/{id}/reviews", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new Dtos.ReviewRequest(pairId, Dtos.ReviewDecision.valueOf(decision),
                                        "reviewer-1"))))
                .andExpect(expected)
                .andReturn();
    }

    @Test
    void reviewConfirmKeepsGradeAndDowngradeLowersOneLevel() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-REVIEW");

        // 确认：维持原分级
        review(ids.get("batchId"), ids.get("GROWING"), "CONFIRM");
        mockMvc.perform(get("/api/alignment-batches/{id}", ids.get("batchId")))
                .andExpect(jsonPath("$.pairs[1].reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.pairs[1].finalGrade").value("GROWING"))
                .andExpect(jsonPath("$.pairs[1].reviewer").value("reviewer-1"));

        // 降级：SEVERE -> GROWING
        review(ids.get("batchId"), ids.get("SEVERE"), "DOWNGRADE");
        mockMvc.perform(get("/api/alignment-batches/{id}", ids.get("batchId")))
                .andExpect(jsonPath("$.pairs[2].reviewStatus").value("DOWNGRADED"))
                .andExpect(jsonPath("$.pairs[2].finalGrade").value("GROWING"));
    }

    @Test
    void reviewStablePairRejected() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-STABLE");
        review(ids.get("batchId"), ids.get("STABLE"), "CONFIRM", status().isUnprocessableEntity());
    }

    @Test
    void duplicateReviewRejected() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-DUP");
        review(ids.get("batchId"), ids.get("GROWING"), "CONFIRM");
        review(ids.get("batchId"), ids.get("GROWING"), "DOWNGRADE", status().isConflict());
        // 数据保持首次复核结论
        mockMvc.perform(get("/api/alignment-batches/{id}", ids.get("batchId")))
                .andExpect(jsonPath("$.pairs[1].reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.pairs[1].finalGrade").value("GROWING"));
    }

    @Test
    void sealRequiresAllGrowingAndSevereReviewed() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-SEAL-PENDING");
        // 尚有 PENDING 复核，封板拒绝
        mockMvc.perform(post("/api/alignment-batches/{id}/seal", ids.get("batchId")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/alignment-batches/{id}", ids.get("batchId")))
                .andExpect(jsonPath("$.status").value("ALIGNED"));

        review(ids.get("batchId"), ids.get("GROWING"), "CONFIRM");
        review(ids.get("batchId"), ids.get("SEVERE"), "CONFIRM");
        mockMvc.perform(post("/api/alignment-batches/{id}/seal", ids.get("batchId")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void resealIsIdempotentSuccess() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-RESEAL");
        review(ids.get("batchId"), ids.get("GROWING"), "CONFIRM");
        review(ids.get("batchId"), ids.get("SEVERE"), "DOWNGRADE");

        MvcResult first = mockMvc.perform(post("/api/alignment-batches/{id}/seal", ids.get("batchId")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/alignment-batches/{id}/seal", ids.get("batchId")))
                .andExpect(status().isOk())
                .andReturn();
        // 重复封板返回首次封板结果
        assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM alignment_batch WHERE status = 'SEALED'", Long.class).intValue());
    }

    @Test
    void reviewAfterSealRejectedWithoutDataChange() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-LATE-REVIEW");
        review(ids.get("batchId"), ids.get("GROWING"), "CONFIRM");
        // SEVERE 保持 PENDING 时不能封板，先确认再封板
        review(ids.get("batchId"), ids.get("SEVERE"), "CONFIRM");
        mockMvc.perform(post("/api/alignment-batches/{id}/seal", ids.get("batchId")))
                .andExpect(status().isOk());

        // 迟到复核：拒绝且不改数据
        review(ids.get("batchId"), ids.get("GROWING"), "DOWNGRADE", status().isConflict());
        mockMvc.perform(get("/api/alignment-batches/{id}", ids.get("batchId")))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.pairs[1].reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.pairs[1].finalGrade").value("GROWING"));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT review_status FROM match_pair WHERE id = ?", String.class, ids.get("GROWING")));
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        mockMvc.perform(get("/api/alignment-batches/{id}", 999999))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/alignment-batches/{id}/seal", 999999))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/alignment-batches/{id}/reviews", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new Dtos.ReviewRequest(1L, Dtos.ReviewDecision.CONFIRM, "r"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void pairFromOtherBatchRejected() throws Exception {
        Map<String, Long> ids = importSampleBatch("REQ-PAIR-A");
        // 同一管段再次导入另一批次
        MvcResult second = mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.sampleImport("REQ-PAIR-B"))))
                .andExpect(status().isCreated())
                .andReturn();
        long otherBatchId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("batchId").asLong();
        // 批次B的复核请求引用批次A的匹配对 -> 422
        review(otherBatchId, ids.get("GROWING"), "CONFIRM", status().isUnprocessableEntity());
    }
}
