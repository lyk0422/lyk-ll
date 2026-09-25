package com.example.starter.blast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * requestId 幂等测试：同键同参返回首次结果、同键异参 409 且不改变流程状态、
 * 备案/复核/封档各自幂等。
 */
class IdempotencyReplayTest extends AbstractBlastIntegrationTest {

    private static final Instant PLANNED = Instant.parse("2026-09-26T02:00:00Z");

    @Test
    void sameApplyRequestReplaysFirstResult() {
        var first = post("/api/blast/applications", new ApplyBody(
                "idem-apply-1", MINE, "ZONE-A", "申请人甲",
                PLANNED.toString(), new BigDecimal("200.000")));
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        long firstId = first.getBody().get("id").asLong();

        var second = post("/api/blast/applications", new ApplyBody(
                "idem-apply-1", MINE, "ZONE-A", "申请人甲",
                PLANNED.toString(), new BigDecimal("200.000")));
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        // 同键同参返回首次创建的同一申请单，不重复落单
        assertThat(second.getBody().get("id").asLong()).isEqualTo(firstId);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_application WHERE request_id = ?", Long.class, "idem-apply-1");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsConflictsAndKeepsState() {
        var first = post("/api/blast/applications", new ApplyBody(
                "idem-apply-2", MINE, "ZONE-A", "申请人甲",
                PLANNED.toString(), new BigDecimal("200.000")));
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        // 同键但药量不同：409，且不得新建/修改申请
        var conflict = post("/api/blast/applications", new ApplyBody(
                "idem-apply-2", MINE, "ZONE-A", "申请人甲",
                PLANNED.toString(), new BigDecimal("210.000")));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);

        var state = jdbc.queryForMap(
                "SELECT charge_per_delay_kg AS charge, status FROM blast_application WHERE request_id = ?",
                "idem-apply-2");
        assertThat(state.get("charge")).isEqualTo(new BigDecimal("200.000"));
        assertThat(state.get("status")).isEqualTo("SUBMITTED");
    }

    @Test
    void rejectedFirstResultReplaysSameRejection() {
        // 首次超药量被拒并缓存；同键同参重放仍返回首次 422，且始终不产生申请单
        for (int i = 0; i < 2; i++) {
            var resp = post("/api/blast/applications", new ApplyBody(
                    "idem-apply-3", MINE, "ZONE-A", "申请人甲",
                    PLANNED.toString(), new BigDecimal("999.000")));
            assertThat(resp.getStatusCode().value()).isEqualTo(422);
            assertThat(resp.getBody().get("message").asText()).contains("超过爆区备案上限");
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_application WHERE request_id = ?", Long.class, "idem-apply-3");
        assertThat(count).isZero();
    }

    @Test
    void filingRequiresApprovalAndIsIdempotent() {
        long applicationId = applyOk("idem-file-app", "ZONE-A", PLANNED, new BigDecimal("200.000"));

        // 未审批备案一律拒绝（首次拒绝结果也按同键重放）
        var rejected = post("/api/blast/filings", new FilingBody(
                "idem-file-1", applicationId, Instant.parse("2026-09-26T02:05:00Z").toString()));
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(rejected.getBody().get("message").asText()).contains("未审批通过");

        var rejectedAgain = post("/api/blast/filings", new FilingBody(
                "idem-file-1", applicationId, Instant.parse("2026-09-26T02:05:00Z").toString()));
        assertThat(rejectedAgain.getStatusCode().value()).isEqualTo(409);

        // 审批通过后换一个备案键完成备案；重复备案同键同参返回首次 FILED 结果
        approveOk(applicationId);
        var filed = post("/api/blast/filings", new FilingBody(
                "idem-file-2", applicationId, Instant.parse("2026-09-26T02:10:00Z").toString()));
        assertThat(filed.getStatusCode().value()).isEqualTo(200);
        assertThat(filed.getBody().get("status").asText()).isEqualTo("FILED");

        var replay = post("/api/blast/filings", new FilingBody(
                "idem-file-2", applicationId, Instant.parse("2026-09-26T02:10:00Z").toString()));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("status").asText()).isEqualTo("FILED");
        assertThat(applicationStatus(applicationId)).isEqualTo("FILED");
    }

    @Test
    void filingSameKeyDifferentApplicationConflicts() {
        long app1 = applyOk("idem-multi-1", "ZONE-A",
                Instant.parse("2026-09-26T02:00:00Z"), new BigDecimal("100.000"));
        long app2 = applyOk("idem-multi-2", "ZONE-A",
                Instant.parse("2026-09-26T03:00:00Z"), new BigDecimal("100.000"));
        approveOk(app1);
        approveOk(app2);

        var first = post("/api/blast/filings", new FilingBody(
                "idem-file-x", app1, Instant.parse("2026-09-26T02:10:00Z").toString()));
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        var conflict = post("/api/blast/filings", new FilingBody(
                "idem-file-x", app2, Instant.parse("2026-09-26T03:10:00Z").toString()));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(applicationStatus(app2)).isEqualTo("APPROVED");
    }
}
