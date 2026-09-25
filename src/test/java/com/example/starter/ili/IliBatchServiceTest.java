package com.example.starter.ili;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ili.dto.BatchResultResponse;
import com.example.starter.ili.dto.DefectInput;
import com.example.starter.ili.dto.PairView;
import com.example.starter.ili.dto.ReviewRequest;
import com.example.starter.ili.dto.SealResponse;
import com.example.starter.ili.dto.SubmitBatchRequest;

/**
 * 基于 H2（MySQL 兼容模式）的批次服务集成测试：幂等重放、异参冲突、整体回滚、
 * 复核规则、封板终态与封板后拒绝修改。
 */
@SpringBootTest
class IliBatchServiceTest {

    /** 固定时钟，验证封板时间等时间字段。 */
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
    private IliBatchService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM ili_match_pair");
        jdbc.update("DELETE FROM ili_defect_reading");
        jdbc.update("DELETE FROM ili_batch");
    }

    private static DefectInput defect(String ref, double position, double depth) {
        return new DefectInput(ref, position, depth);
    }

    /** 构造含一个增长对（5%/年）、一个严重对（15%/年）、一个新发现、一个已消失的请求。 */
    private static SubmitBatchRequest sampleRequest(String requestId) {
        return new SubmitBatchRequest(
                requestId, "SEG-01", "RUN-2020", "RUN-2024",
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1),
                List.of(defect("O-GROW", 100.0, 10.0), defect("O-SEVERE", 200.0, 10.0),
                        defect("O-GONE", 300.0, 40.0)),
                List.of(defect("N-GROW", 100.4, 30.0), defect("N-SEVERE", 200.3, 70.0),
                        defect("N-NEW", 500.0, 25.0)));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void submitAlignsAndGrades() {
        BatchResultResponse result = service.submit(sampleRequest("REQ-1"));

        assertEquals("ALIGNED", result.status());
        assertEquals(FIXED_NOW, result.createdAt());
        assertNull(result.sealedAt());
        assertEquals(2, result.pairs().size());

        PairView grow = result.pairs().stream()
                .filter(p -> p.oldDefectRef().equals("O-GROW")).findFirst().orElseThrow();
        assertEquals("N-GROW", grow.newDefectRef());
        assertEquals(0, grow.distanceM().compareTo(new java.math.BigDecimal("0.400")));
        assertEquals("GROWING", grow.grade());
        assertEquals("PENDING", grow.reviewStatus());

        PairView severe = result.pairs().stream()
                .filter(p -> p.oldDefectRef().equals("O-SEVERE")).findFirst().orElseThrow();
        assertEquals("SEVERE", severe.grade());

        assertEquals(List.of("N-NEW"), result.newFindings().stream().map(r -> r.defectRef()).toList());
        assertEquals(List.of("O-GONE"), result.disappeared().stream().map(r -> r.defectRef()).toList());

        assertEquals(1, count("ili_batch"));
        assertEquals(6, count("ili_defect_reading"));
        assertEquals(2, count("ili_match_pair"));
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        BatchResultResponse first = service.submit(sampleRequest("REQ-REPLAY"));
        BatchResultResponse replay = service.submit(sampleRequest("REQ-REPLAY"));

        assertEquals(first, replay);
        assertEquals(1, count("ili_batch"));
        assertEquals(6, count("ili_defect_reading"));
        assertEquals(2, count("ili_match_pair"));
    }

    @Test
    void sameKeyDifferentParamsConflictsWithoutPartialWrites() {
        service.submit(sampleRequest("REQ-CONFLICT"));

        SubmitBatchRequest base = sampleRequest("REQ-CONFLICT");
        SubmitBatchRequest tampered = new SubmitBatchRequest(
                base.requestId(), base.segmentId(), base.runOldId(), base.runNewId(),
                base.runOldDate(), base.runNewDate(), base.oldDefects(),
                List.of(defect("N-GROW", 100.4, 30.0), defect("N-SEVERE", 200.3, 70.0),
                        defect("N-NEW", 500.0, 26.0)));
        ApiException ex = assertThrows(ApiException.class, () -> service.submit(tampered));
        assertEquals(HttpStatus.CONFLICT, ex.status());

        assertEquals(1, count("ili_batch"));
        assertEquals(6, count("ili_defect_reading"));
        assertEquals(2, count("ili_match_pair"));
    }

    @Test
    void duplicateDefectRefRollsBackWholeBatch() {
        SubmitBatchRequest bad = new SubmitBatchRequest(
                "REQ-ROLLBACK", "SEG-01", "RUN-2020", "RUN-2024",
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1),
                List.of(defect("O-DUP", 100.0, 10.0), defect("O-DUP", 101.0, 20.0)),
                List.of(defect("N-1", 100.0, 10.0)));

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());

        // 整体回滚：批次、读数、匹配对均无残留。
        assertEquals(0, count("ili_batch"));
        assertEquals(0, count("ili_defect_reading"));
        assertEquals(0, count("ili_match_pair"));
    }

    @Test
    void reviewConfirmsAndDowngrades() {
        BatchResultResponse batch = service.submit(sampleRequest("REQ-REVIEW"));
        PairView severe = batch.pairs().stream()
                .filter(p -> p.grade().equals("SEVERE")).findFirst().orElseThrow();
        PairView grow = batch.pairs().stream()
                .filter(p -> p.grade().equals("GROWING")).findFirst().orElseThrow();

        PairView confirmed = service.review(batch.batchId(),
                new ReviewRequest(severe.pairId(), ReviewDecision.CONFIRM, "reviewer-a", "同意严重"));
        assertEquals("CONFIRMED", confirmed.reviewStatus());
        assertEquals("SEVERE", confirmed.finalGrade());

        PairView downgraded = service.review(batch.batchId(),
                new ReviewRequest(grow.pairId(), ReviewDecision.DOWNGRADE, "reviewer-b", "降为稳定"));
        assertEquals("DOWNGRADED", downgraded.reviewStatus());
        assertEquals("STABLE", downgraded.finalGrade());

        // 重复复核被拒绝。
        ApiException ex = assertThrows(ApiException.class, () -> service.review(batch.batchId(),
                new ReviewRequest(grow.pairId(), ReviewDecision.CONFIRM, "reviewer-c", null)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void reviewOnStablePairRejected() {
        SubmitBatchRequest req = new SubmitBatchRequest(
                "REQ-STABLE", "SEG-01", "RUN-2020", "RUN-2024",
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1),
                List.of(defect("O-1", 100.0, 10.0)),
                List.of(defect("N-1", 100.0, 12.0)));
        BatchResultResponse batch = service.submit(req);
        assertEquals("STABLE", batch.pairs().get(0).grade());

        ApiException ex = assertThrows(ApiException.class, () -> service.review(batch.batchId(),
                new ReviewRequest(batch.pairs().get(0).pairId(), ReviewDecision.CONFIRM, "r", null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void sealIsTerminalAndIdempotent() {
        BatchResultResponse batch = service.submit(sampleRequest("REQ-SEAL"));

        SealResponse first = service.seal(batch.batchId());
        assertEquals("SEALED", first.status());
        assertEquals(FIXED_NOW, first.sealedAt());

        // 重复封板：幂等成功，封板时间不变。
        SealResponse again = service.seal(batch.batchId());
        assertEquals(first, again);

        // 封板后迟到复核被拒绝且不改数据。
        PairView severe = batch.pairs().stream()
                .filter(p -> p.grade().equals("SEVERE")).findFirst().orElseThrow();
        ApiException ex = assertThrows(ApiException.class, () -> service.review(batch.batchId(),
                new ReviewRequest(severe.pairId(), ReviewDecision.CONFIRM, "late-reviewer", "迟到")));
        assertEquals(HttpStatus.CONFLICT, ex.status());

        PairView after = service.getBatch(batch.batchId()).pairs().stream()
                .filter(p -> p.pairId() == severe.pairId()).findFirst().orElseThrow();
        assertEquals("PENDING", after.reviewStatus());
        assertNull(after.finalGrade());

        // 封板后同键重放仍返回首次结果（状态为 SEALED）。
        BatchResultResponse replay = service.submit(sampleRequest("REQ-SEAL"));
        assertEquals("SEALED", replay.status());
        assertNotNull(replay.sealedAt());
    }

    @Test
    void unknownBatchRejected() {
        ApiException sealEx = assertThrows(ApiException.class, () -> service.seal(999999L));
        assertEquals(HttpStatus.NOT_FOUND, sealEx.status());
        ApiException getEx = assertThrows(ApiException.class, () -> service.getBatch(999999L));
        assertEquals(HttpStatus.NOT_FOUND, getEx.status());
    }

    @Test
    void invalidDateOrderRejected() {
        SubmitBatchRequest bad = new SubmitBatchRequest(
                "REQ-DATES", "SEG-01", "RUN-2020", "RUN-2024",
                LocalDate.of(2024, 1, 1), LocalDate.of(2020, 1, 1),
                List.of(defect("O-1", 100.0, 10.0)),
                List.of(defect("N-1", 100.0, 10.0)));
        ApiException ex = assertThrows(ApiException.class, () -> service.submit(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals(0, count("ili_batch"));
    }

    @Test
    void uniquePairConstraintsHold() {
        BatchResultResponse batch = service.submit(sampleRequest("REQ-UNIQ"));
        Integer dupOld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT old_reading_id FROM ili_match_pair GROUP BY old_reading_id HAVING COUNT(*) > 1) t",
                Integer.class);
        Integer dupNew = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT new_reading_id FROM ili_match_pair GROUP BY new_reading_id HAVING COUNT(*) > 1) t",
                Integer.class);
        assertEquals(0, dupOld);
        assertEquals(0, dupNew);
        assertTrue(batch.pairs().size() > 0);
    }
}
