package com.example.starter.blast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 振动回传齐全判定、效果评估封档终态与迟到读数事件测试。
 * ZONE-A 备案测点为 P1/P2/P3/P4 共 4 个。
 */
class EvaluationArchiveTest extends AbstractBlastIntegrationTest {

    private static final Instant T0400 = Instant.parse("2026-09-26T04:00:00Z");

    @Test
    void evaluationRejectedUntilReadingsComplete() {
        long applicationId = filedApp();

        // 一个测点读数都没有：不能封档
        var none = post("/api/blast/evaluations", new EvaluationBody(
                "eval-0", applicationId, "评估员丁", "结论待定"));
        assertThat(none.getStatusCode().value()).isEqualTo(409);
        assertThat(none.getBody().get("message").asText()).contains("未回传齐全");

        reading(applicationId, "P1", "04:05:01", "5.000");
        reading(applicationId, "P2", "04:05:02", "4.000");
        reading(applicationId, "P3", "04:05:03", "6.000");

        // 只回传 3/4 个测点：仍不能封档
        var partial = post("/api/blast/evaluations", new EvaluationBody(
                "eval-1", applicationId, "评估员丁", "结论待定"));
        assertThat(partial.getStatusCode().value()).isEqualTo(409);
        assertThat(partial.getBody().get("message").asText()).contains("已回传测点 3 个");
        assertThat(applicationStatus(applicationId)).isEqualTo("FILED");
    }

    @Test
    void archiveAfterCompleteReadingsAndReplayIsIdempotent() {
        long applicationId = filedApp();
        sendAllReadings(applicationId, "5.000");

        var archived = post("/api/blast/evaluations", new EvaluationBody(
                "eval-ok", applicationId, "评估员丁", "爆破效果合格"));
        assertThat(archived.getStatusCode().value()).isEqualTo(201);
        assertThat(archived.getBody().get("conclusion").asText()).isEqualTo("爆破效果合格");
        assertThat(applicationStatus(applicationId)).isEqualTo("ARCHIVED");

        // 同键同参重复封档：幂等成功，严格重放首次 201 结果
        var replay = post("/api/blast/evaluations", new EvaluationBody(
                "eval-ok", applicationId, "评估员丁", "爆破效果合格"));
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody().get("conclusion").asText()).isEqualTo("爆破效果合格");

        // 换键但对同一终态作业重复封档：仍幂等成功（200），不新增评估记录，不改变结论
        var anotherKey = post("/api/blast/evaluations", new EvaluationBody(
                "eval-ok-2", applicationId, "评估员丁", "试图改写结论"));
        assertThat(anotherKey.getStatusCode().value()).isEqualTo(200);
        assertThat(anotherKey.getBody().get("conclusion").asText()).isEqualTo("爆破效果合格");

        Long evaluations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM effect_evaluation WHERE application_id = ?",
                Long.class, applicationId);
        assertThat(evaluations).isEqualTo(1);
        assertThat(applicationStatus(applicationId)).isEqualTo("ARCHIVED");
    }

    @Test
    void duplicateReadingIsDeduplicatedBeforeArchive() {
        long applicationId = filedApp();
        var first = reading(applicationId, "P1", "04:05:01", "5.000");
        assertThat(first.getBody().get("accepted").asBoolean()).isTrue();
        assertThat(first.getBody().get("duplicate").asBoolean()).isFalse();

        var duplicate = reading(applicationId, "P1", "04:05:01", "5.000");
        assertThat(duplicate.getBody().get("accepted").asBoolean()).isFalse();
        assertThat(duplicate.getBody().get("duplicate").asBoolean()).isTrue();

        // 同一测点不同采样时刻是不同读数，只计一个测点
        var otherSample = reading(applicationId, "P1", "04:06:01", "5.100");
        assertThat(otherSample.getBody().get("accepted").asBoolean()).isTrue();

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vibration_reading WHERE application_id = ? AND point_code = 'P1'",
                Long.class, applicationId);
        assertThat(rows).isEqualTo(2);
        Integer distinctPoints = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT point_code) FROM vibration_reading WHERE application_id = ?",
                Integer.class, applicationId);
        assertThat(distinctPoints).isEqualTo(1);
    }

    @Test
    void lateReadingAfterArchiveOnlyRecordsEventWithoutChangingConclusion() {
        long applicationId = filedApp();
        sendAllReadings(applicationId, "5.000");
        var archived = post("/api/blast/evaluations", new EvaluationBody(
                "eval-late", applicationId, "评估员丁", "封档结论"));
        assertThat(archived.getStatusCode().value()).isEqualTo(201);

        // 封档后迟到的超限读数：只记迟到事件，不冻结、不改结论
        var late = reading(applicationId, "P2", "05:30:00", "999.000");
        assertThat(late.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(late.getBody().get("lateEvent").asBoolean()).isTrue();
        assertThat(late.getBody().get("frozen").asBoolean()).isFalse();
        assertThat(mineFrozen()).isFalse();
        assertThat(applicationStatus(applicationId)).isEqualTo("ARCHIVED");

        Long lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_reading_event WHERE application_id = ? AND point_code = 'P2' "
                        + "AND sample_time = ?",
                Long.class, applicationId,
                java.sql.Timestamp.from(Instant.parse("2026-09-26T05:30:00Z")));
        assertThat(lateEvents).isEqualTo(1);
        String conclusion = jdbc.queryForObject(
                "SELECT conclusion FROM effect_evaluation WHERE application_id = ?",
                String.class, applicationId);
        assertThat(conclusion).isEqualTo("封档结论");

        // 迟到事件同样按自然键去重
        var lateAgain = reading(applicationId, "P2", "05:30:00", "999.000");
        assertThat(lateAgain.getBody().get("accepted").asBoolean()).isFalse();
        assertThat(lateAgain.getBody().get("duplicate").asBoolean()).isTrue();
    }

    @Test
    void cannotEvaluateApplicationThatWasNotFiled() {
        long applicationId = applyOk("eval-not-filed", "ZONE-A", T0400, new BigDecimal("160.000"));
        approveOk(applicationId);
        var resp = post("/api/blast/evaluations", new EvaluationBody(
                "eval-premature", applicationId, "评估员丁", "提前封档"));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("message").asText()).contains("APPROVED");
    }

    private long filedApp() {
        long applicationId = applyOk("eval-app-" + System.nanoTime(), "ZONE-A", T0400,
                new BigDecimal("160.000"));
        approveOk(applicationId);
        filingOk("eval-file-" + System.nanoTime(), applicationId,
                Instant.parse("2026-09-26T04:04:00Z"));
        return applicationId;
    }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> reading(
            long applicationId, String point, String hhmmss, String velocity) {
        return post("/api/blast/readings", new ReadingBody(
                applicationId, "ZONE-A", point,
                "2026-09-26T" + hhmmss + "Z", new BigDecimal(velocity)));
    }

    private void sendAllReadings(long applicationId, String velocity) {
        String[] points = {"P1", "P2", "P3", "P4"};
        for (int i = 0; i < points.length; i++) {
            var resp = reading(applicationId, points[i], String.format("04:05:%02d", i + 1), velocity);
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getBody().get("accepted").asBoolean()).isTrue();
        }
    }
}
