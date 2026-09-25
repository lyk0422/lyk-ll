package com.example.starter.blast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超限冻结立即生效范围：在途申请驳回、已批准未起爆作废、冻结前 FILED 历史不受影响；
 * 冻结期间拒收新申请；监管复核解冻幂等，解冻后恢复受理。
 */
class FreezeEffectTest extends AbstractBlastIntegrationTest {

    private static final Instant T0200 = Instant.parse("2026-09-26T02:00:00Z");
    private static final Instant T0300 = Instant.parse("2026-09-26T03:00:00Z");
    private static final Instant T0400 = Instant.parse("2026-09-26T04:00:00Z");

    @Test
    void overLimitReadingFreezesMineAndAppliesScopeImmediately() {
        // 冻结前三个作业分别处于 SUBMITTED / APPROVED / FILED
        long submittedApp = applyOk("freeze-submitted", "ZONE-A", T0200, new BigDecimal("160.000"));
        long approvedApp = applyOk("freeze-approved", "ZONE-A", T0300, new BigDecimal("160.000"));
        approveOk(approvedApp);
        long filedApp = applyOk("freeze-filed", "ZONE-A", T0400, new BigDecimal("160.000"));
        approveOk(filedApp);
        filingOk("freeze-filing", filedApp, Instant.parse("2026-09-26T04:05:00Z"));

        // P1 阈值 20mm/s，回传 25mm/s 超限，触发冻结
        var reading = post("/api/blast/readings", new ReadingBody(
                filedApp, "ZONE-A", "P1",
                Instant.parse("2026-09-26T04:05:01Z").toString(), new BigDecimal("25.000")));
        assertThat(reading.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(reading.getBody().get("accepted").asBoolean()).isTrue();
        assertThat(reading.getBody().get("overLimit").asBoolean()).isTrue();
        assertThat(reading.getBody().get("frozen").asBoolean()).isTrue();

        // 冻结立即生效
        assertThat(mineFrozen()).isTrue();
        assertThat(applicationStatus(submittedApp)).isEqualTo("REJECTED");
        assertThat(applicationStatus(approvedApp)).isEqualTo("VOIDED");
        // 冻结前的合法历史不受影响
        assertThat(applicationStatus(filedApp)).isEqualTo("FILED");

        String submittedReason = jdbc.queryForObject(
                "SELECT reject_reason FROM blast_application WHERE id = ?", String.class, submittedApp);
        String voidedReason = jdbc.queryForObject(
                "SELECT reject_reason FROM blast_application WHERE id = ?", String.class, approvedApp);
        assertThat(submittedReason).contains("矿区冻结");
        assertThat(voidedReason).contains("矿区冻结");

        Long freezeEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM freeze_event WHERE mine_code = ?", Long.class, MINE);
        assertThat(freezeEvents).isEqualTo(1);
    }

    @Test
    void anotherOverLimitReadingWhileFrozenDoesNotDuplicateFreezeEvent() {
        long filedApp = filedApplication("freeze2-app", "freeze2-file", T0400);
        overLimitReading(filedApp, "P1", Instant.parse("2026-09-26T04:05:01Z"), "25.000");
        assertThat(mineFrozen()).isTrue();

        // 冻结期间再次超限读数：不再翻转冻结、不重复落冻结事件
        var second = post("/api/blast/readings", new ReadingBody(
                filedApp, "ZONE-A", "P2",
                Instant.parse("2026-09-26T04:05:02Z").toString(), new BigDecimal("99.000")));
        assertThat(second.getBody().get("frozen").asBoolean()).isTrue();
        Long freezeEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM freeze_event WHERE mine_code = ?", Long.class, MINE);
        assertThat(freezeEvents).isEqualTo(1);
    }

    @Test
    void frozenMineRejectsNewApplicationsAndFilings() {
        // 冻结前：一个已批准未起爆（ZONE-B）、一个已备案（ZONE-A，用于触发冻结）
        long approvedApp = applyOk("freeze3-approved", "ZONE-B", T0300, new BigDecimal("100.000"));
        approveOk(approvedApp);
        long filedApp = filedApplication("freeze3-app", "freeze3-file", T0400);
        overLimitReading(filedApp, "P1", Instant.parse("2026-09-26T04:05:01Z"), "25.000");

        // 冻结立即生效：冻结前的已批准未起爆作业被作废
        assertThat(applicationStatus(approvedApp)).isEqualTo("VOIDED");

        // 冻结期间新申请拒收
        var newApply = post("/api/blast/applications", new ApplyBody(
                "freeze3-new", MINE, "ZONE-B", "申请人甲",
                Instant.parse("2026-09-26T05:00:00Z").toString(), new BigDecimal("100.000")));
        assertThat(newApply.getStatusCode().value()).isEqualTo(423);

        // 已作废作业的起爆备案被拒（状态 VOIDED）
        var filing = post("/api/blast/filings", new FilingBody(
                "freeze3-filing", approvedApp, Instant.parse("2026-09-26T04:30:00Z").toString()));
        assertThat(filing.getStatusCode().value()).isEqualTo(409);
        assertThat(filing.getBody().get("message").asText()).contains("VOIDED");
        assertThat(applicationStatus(approvedApp)).isEqualTo("VOIDED");
    }

    @Test
    void regulatoryReviewUnfreezesAndIsIdempotent() {
        long filedApp = filedApplication("freeze4-app", "freeze4-file", T0400);
        overLimitReading(filedApp, "P1", Instant.parse("2026-09-26T04:05:01Z"), "25.000");
        assertThat(mineFrozen()).isTrue();

        var unfrozen = post("/api/blast/mines/" + MINE + "/unfreeze",
                new UnfreezeBody("review-1", "监管员丙", true));
        assertThat(unfrozen.getStatusCode().value()).isEqualTo(200);
        assertThat(unfrozen.getBody().get("frozen").asBoolean()).isFalse();
        assertThat(unfrozen.getBody().get("unfreezeReviewer").asText()).isEqualTo("监管员丙");
        assertThat(mineFrozen()).isFalse();

        // 同键同参重放首次解冻结果
        var replay = post("/api/blast/mines/" + MINE + "/unfreeze",
                new UnfreezeBody("review-1", "监管员丙", true));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("frozen").asBoolean()).isFalse();
        Long reviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unfreeze_review WHERE request_id = ?", Long.class, "review-1");
        assertThat(reviews).isEqualTo(1);

        // 已解冻后另一复核请求得到首次 409 结果
        var notFrozen = post("/api/blast/mines/" + MINE + "/unfreeze",
                new UnfreezeBody("review-2", "监管员丙", true));
        assertThat(notFrozen.getStatusCode().value()).isEqualTo(409);

        // 解冻后新申请恢复受理
        var reopened = post("/api/blast/applications", new ApplyBody(
                "freeze4-new", MINE, "ZONE-B", "申请人甲",
                Instant.parse("2026-09-26T05:00:00Z").toString(), new BigDecimal("100.000")));
        assertThat(reopened.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void unfreezeWithoutFreezeReturnsConflict() {
        var resp = post("/api/blast/mines/" + MINE + "/unfreeze",
                new UnfreezeBody("review-never", "监管员丙", true));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
    }

    private long filedApplication(String requestId, String filingRequestId, Instant planned) {
        long applicationId = applyOk(requestId, "ZONE-A", planned, new BigDecimal("160.000"));
        approveOk(applicationId);
        filingOk(filingRequestId, applicationId, planned.plusSeconds(300));
        return applicationId;
    }

    private void overLimitReading(long applicationId, String point, Instant sample, String velocity) {
        var resp = post("/api/blast/readings", new ReadingBody(
                applicationId, "ZONE-A", point, sample.toString(), new BigDecimal(velocity)));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().get("overLimit").asBoolean()).isTrue();
    }
}
