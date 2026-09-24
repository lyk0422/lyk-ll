package com.example.starter.ili;

import com.example.starter.ili.persistence.BatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 集成测试：双回合导入、对齐分级、幂等重放/冲突、整体回滚、复核与封板终态。
 * 使用独立 H2 内存库（MySQL 兼容模式）与固定时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlignmentApiIntegrationTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-25T02:15:30Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM match_pair");
        jdbc.update("DELETE FROM defect_reading");
        jdbc.update("DELETE FROM alignment_batch");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        // 命名内存库使用 DB_CLOSE_DELAY=-1，测试结束后显式释放
        jdbc.execute("DROP ALL OBJECTS");
    }

    private static final String IMPORT_JSON = """
            {
              "requestId": "req-001",
              "segmentId": "SEG-7",
              "oldRun": {"runId": "RUN-2020", "inspectedAt": "2020-01-01", "readings": [
                {"readingKey": "o1", "stationM": 100.0, "depthPct": 10},
                {"readingKey": "o2", "stationM": 200.0, "depthPct": 10},
                {"readingKey": "o3", "stationM": 300.0, "depthPct": 10}
              ]},
              "newRun": {"runId": "RUN-2024", "inspectedAt": "2024-01-01", "readings": [
                {"readingKey": "n1", "stationM": 100.5, "depthPct": 30},
                {"readingKey": "n2", "stationM": 200.0, "depthPct": 70},
                {"readingKey": "n3", "stationM": 999.0, "depthPct": 5}
              ]}
            }
            """;

    private long importBatch(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode getBatchJson(long id) throws Exception {
        MvcResult result = mvc.perform(get("/api/alignment-batches/{id}", id))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void importAlignsAndGradesWithToleranceBoundary() throws Exception {
        long id = importBatch(IMPORT_JSON);
        mvc.perform(get("/api/alignment-batches/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.segmentId").value("SEG-7"))
                .andExpect(jsonPath("$.matches.length()").value(2))
                // o1↔n1：里程差恰为 0.5 m 边界匹配，4 年增长 20% → 5.0 %/年 → GROWING
                .andExpect(jsonPath("$.matches[0].oldReadingKey").value("o1"))
                .andExpect(jsonPath("$.matches[0].newReadingKey").value("n1"))
                .andExpect(jsonPath("$.matches[0].distanceM").value(0.5))
                .andExpect(jsonPath("$.matches[0].growthRatePct").value(5.0))
                .andExpect(jsonPath("$.matches[0].grade").value("GROWING"))
                .andExpect(jsonPath("$.matches[0].reviewStatus").value("PENDING"))
                // o2↔n2：4 年增长 60% → 15.0 %/年 → SEVERE
                .andExpect(jsonPath("$.matches[1].oldReadingKey").value("o2"))
                .andExpect(jsonPath("$.matches[1].grade").value("SEVERE"))
                .andExpect(jsonPath("$.newFindings[0]").value("n3"))
                .andExpect(jsonPath("$.disappeared[0]").value("o3"));
    }

    @Test
    void replaySameRequestReturnsFirstResult() throws Exception {
        long first = importBatch(IMPORT_JSON);
        long second = importBatch(IMPORT_JSON);
        assertEquals(first, second);
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM alignment_batch", Integer.class);
        Integer readings = jdbc.queryForObject("SELECT COUNT(*) FROM defect_reading", Integer.class);
        assertEquals(1, batches);
        assertEquals(6, readings);
    }

    @Test
    void sameKeyDifferentParamsReturns409WithoutPartialWrites() throws Exception {
        importBatch(IMPORT_JSON);
        String tampered = IMPORT_JSON.replace("\"depthPct\": 30", "\"depthPct\": 31");
        mvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict());
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM alignment_batch", Integer.class);
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM match_pair", Integer.class);
        assertEquals(1, batches);
        assertEquals(2, matches);
    }

    @Test
    void importRollsBackEntirelyWhenAnyStatementFails() throws Exception {
        // 读数键超长（>64 字符）在批次插入后才触发约束失败，验证整体回滚
        String longKey = "k".repeat(70);
        String badJson = IMPORT_JSON.replace("\"req-001\"", "\"req-overflow\"")
                .replace("\"o3\"", "\"" + longKey + "\"");
        mvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(badJson))
                .andExpect(status().isBadRequest());
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM alignment_batch", Integer.class);
        Integer readings = jdbc.queryForObject("SELECT COUNT(*) FROM defect_reading", Integer.class);
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM match_pair", Integer.class);
        assertEquals(0, batches);
        assertEquals(0, readings);
        assertEquals(0, matches);
    }

    @Test
    void duplicateReadingKeyRejected() throws Exception {
        String dup = IMPORT_JSON.replace("\"req-001\"", "\"req-dup\"")
                .replace("\"n2\"", "\"n1\"");
        mvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isBadRequest());
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM alignment_batch", Integer.class);
        assertEquals(0, batches);
    }

    @Test
    void newRunMustPostdateOldRun() throws Exception {
        String bad = IMPORT_JSON.replace("\"req-001\"", "\"req-date\"")
                .replace("2024-01-01", "2019-01-01");
        mvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reviewConfirmDowngradeThenSealIsFinal() throws Exception {
        long id = importBatch(IMPORT_JSON);
        JsonNode batch = getBatchJson(id);
        long growingId = matchIdOf(batch, "o1");
        long severeId = matchIdOf(batch, "o2");

        String reviewJson = """
                {"items": [
                  {"matchId": %d, "action": "CONFIRM", "reviewer": "zhangsan"},
                  {"matchId": %d, "action": "DOWNGRADE", "downgradedGrade": "GROWING", "reviewer": "lisi"}
                ]}
                """.formatted(growingId, severeId);
        mvc.perform(post("/api/alignment-batches/{id}/reviews", id)
                        .contentType(MediaType.APPLICATION_JSON).content(reviewJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.matches[0].reviewer").value("zhangsan"))
                .andExpect(jsonPath("$.matches[1].reviewStatus").value("DOWNGRADED"))
                .andExpect(jsonPath("$.matches[1].effectiveGrade").value("GROWING"));

        // 重复提交同一复核结论 → 409
        mvc.perform(post("/api/alignment-batches/{id}/reviews", id)
                        .contentType(MediaType.APPLICATION_JSON).content(reviewJson))
                .andExpect(status().isConflict());

        // 封板；固定时钟 → sealedAt 确定
        mvc.perform(post("/api/alignment-batches/{id}/seal", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").value("2026-09-25T10:15:30"));

        // 重复封板幂等成功
        mvc.perform(post("/api/alignment-batches/{id}/seal", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").value("2026-09-25T10:15:30"));

        // 封板后迟到复核拒绝且不改数据
        String lateReview = """
                {"items": [{"matchId": %d, "action": "DOWNGRADE", "downgradedGrade": "STABLE", "reviewer": "wangwu"}]}
                """.formatted(severeId);
        mvc.perform(post("/api/alignment-batches/{id}/reviews", id)
                        .contentType(MediaType.APPLICATION_JSON).content(lateReview))
                .andExpect(status().isConflict());
        JsonNode after = getBatchJson(id);
        assertEquals("SEALED", after.get("status").asText());
        assertEquals("DOWNGRADED", after.get("matches").get(1).get("reviewStatus").asText());
        assertEquals("GROWING", after.get("matches").get(1).get("effectiveGrade").asText());
        assertEquals("lisi", after.get("matches").get(1).get("reviewer").asText());
    }

    @Test
    void sealRejectedWhileGrowingOrSeverePending() throws Exception {
        long id = importBatch(IMPORT_JSON);
        mvc.perform(post("/api/alignment-batches/{id}/seal", id))
                .andExpect(status().isConflict());
        JsonNode batch = getBatchJson(id);
        assertEquals("OPEN", batch.get("status").asText());
    }

    @Test
    void stableOnlyBatchSealsWithoutReview() throws Exception {
        String stable = """
                {
                  "requestId": "req-stable",
                  "segmentId": "SEG-1",
                  "oldRun": {"runId": "R1", "inspectedAt": "2020-01-01", "readings": [
                    {"readingKey": "o1", "stationM": 10.0, "depthPct": 10}
                  ]},
                  "newRun": {"runId": "R2", "inspectedAt": "2024-01-01", "readings": [
                    {"readingKey": "n1", "stationM": 10.0, "depthPct": 12}
                  ]}
                }
                """;
        long id = importBatch(stable);
        JsonNode batch = getBatchJson(id);
        long matchId = matchIdOf(batch, "o1");
        assertEquals("STABLE", batch.get("matches").get(0).get("grade").asText());

        // 稳定缺陷无需复核：提交复核 → 400
        String reviewJson = """
                {"items": [{"matchId": %d, "action": "CONFIRM", "reviewer": "zhangsan"}]}
                """.formatted(matchId);
        mvc.perform(post("/api/alignment-batches/{id}/reviews", id)
                        .contentType(MediaType.APPLICATION_JSON).content(reviewJson))
                .andExpect(status().isBadRequest());

        // 无待复核增长/严重缺陷，可直接封板
        mvc.perform(post("/api/alignment-batches/{id}/seal", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void downgradeTargetMustBeLowerThanSystemGrade() throws Exception {
        long id = importBatch(IMPORT_JSON);
        JsonNode batch = getBatchJson(id);
        long growingId = matchIdOf(batch, "o1");
        String badReview = """
                {"items": [{"matchId": %d, "action": "DOWNGRADE", "downgradedGrade": "SEVERE", "reviewer": "zhangsan"}]}
                """.formatted(growingId);
        mvc.perform(post("/api/alignment-batches/{id}/reviews", id)
                        .contentType(MediaType.APPLICATION_JSON).content(badReview))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        mvc.perform(get("/api/alignment-batches/{id}", 424242))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/alignment-batches/{id}/seal", 424242))
                .andExpect(status().isNotFound());
    }

    private static long matchIdOf(JsonNode batch, String oldKey) {
        for (JsonNode m : batch.get("matches")) {
            if (m.get("oldReadingKey").asText().equals(oldKey)) {
                return m.get("id").asLong();
            }
        }
        throw new AssertionError("匹配对不存在: " + oldKey);
    }

    @Autowired
    private BatchRepository batchRepository;
    @Autowired
    private com.example.starter.ili.persistence.ReadingRepository readingRepository;
    @Autowired
    private com.example.starter.ili.persistence.MatchRepository matchRepository;

    @Test
    void h2EnforcesUniqueReadingPerMatchPair() {
        // 唯一约束：同一读数不允许进入两个匹配对（uk_match_old / uk_match_new）
        long batchId = batchRepository.insert("req-uk", "SEG-9", "R1", "R2",
                java.time.LocalDate.of(2020, 1, 1), java.time.LocalDate.of(2024, 1, 1), "fp-uk");
        long o1 = readingRepository.insert(batchId, com.example.starter.ili.model.RunSide.OLD,
                "o1", new java.math.BigDecimal("10.0"), new java.math.BigDecimal("10"), true);
        long o2 = readingRepository.insert(batchId, com.example.starter.ili.model.RunSide.OLD,
                "o2", new java.math.BigDecimal("20.0"), new java.math.BigDecimal("10"), true);
        long n1 = readingRepository.insert(batchId, com.example.starter.ili.model.RunSide.NEW,
                "n1", new java.math.BigDecimal("10.0"), new java.math.BigDecimal("12"), true);
        long n2 = readingRepository.insert(batchId, com.example.starter.ili.model.RunSide.NEW,
                "n2", new java.math.BigDecimal("20.0"), new java.math.BigDecimal("12"), true);
        matchRepository.insert(batchId, o1, n1, new java.math.BigDecimal("0.0"),
                new java.math.BigDecimal("0.5"), com.example.starter.ili.model.Grade.STABLE);
        // 旧读数 o1 再次成对 → 违反 uk_match_old
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> matchRepository.insert(batchId, o1, n2, new java.math.BigDecimal("0.0"),
                        new java.math.BigDecimal("0.5"), com.example.starter.ili.model.Grade.STABLE));
        // 新读数 n1 再次成对 → 违反 uk_match_new
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> matchRepository.insert(batchId, o2, n1, new java.math.BigDecimal("0.0"),
                        new java.math.BigDecimal("0.5"), com.example.starter.ili.model.Grade.STABLE));
    }
}
