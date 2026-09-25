package com.example.starter.blast;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受理时段边界（矿区时区 Asia/Shanghai，UTC+8）与爆区单段药量上限测试。
 * 允许窗口当地 06:00（含）至 22:00（不含），即 UTC 22:00（前日，含）至次日 14:00（不含）。
 */
class TimeWindowAndChargeTest extends AbstractBlastIntegrationTest {

    @Test
    void exactlyLocalSixAccepted() {
        // 当地 06:00:00 = UTC 前一日 22:00:00，恰在 06:00 受理
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-window-0600", MINE, "ZONE-A", "申请人甲",
                Instant.parse("2026-09-25T22:00:00Z").toString(), new BigDecimal("200.000")));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().get("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void exactlyLocalTwentyTwoRejected() {
        // 当地 22:00:00 = UTC 14:00:00，恰在 22:00 拒绝
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-window-2200", MINE, "ZONE-A", "申请人甲",
                Instant.parse("2026-09-26T14:00:00Z").toString(), new BigDecimal("200.000")));
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody().get("message").asText()).contains("作业时段");
    }

    @Test
    void oneSecondBeforeSixRejected() {
        // 当地 05:59:59 = UTC 21:59:59，窗口外拒绝
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-window-0559", MINE, "ZONE-A", "申请人甲",
                Instant.parse("2026-09-25T21:59:59Z").toString(), new BigDecimal("200.000")));
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void oneSecondBeforeTwentyTwoAccepted() {
        // 当地 21:59:59 = UTC 13:59:59，仍在窗口内
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-window-2159", MINE, "ZONE-A", "申请人甲",
                Instant.parse("2026-09-26T13:59:59Z").toString(), new BigDecimal("200.000")));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void chargeAtLimitAccepted() {
        // ZONE-A 上限 500kg，等于上限受理
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-charge-eq", MINE, "ZONE-A", "申请人甲",
                Instant.parse("2026-09-26T02:00:00Z").toString(), new BigDecimal("500.000")));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void chargeOverLimitRejected() {
        // 超过备案上限直接拒绝
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-charge-over", MINE, "ZONE-A", "申请人甲",
                Instant.parse("2026-09-26T02:00:00Z").toString(), new BigDecimal("500.001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        JsonNode body = resp.getBody();
        assertThat(body.get("rejected").asBoolean()).isTrue();
        assertThat(body.get("message").asText()).contains("超过爆区备案上限");
        // 直接拒绝不得产生申请单
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_application WHERE request_id = ?",
                Long.class, "req-charge-over");
        assertThat(count).isZero();
    }

    @Test
    void zoneSpecificLimitApplied() {
        // ZONE-C 上限 120kg，150kg 拒绝
        var resp = post("/api/blast/applications", new ApplyBody(
                "req-charge-zone-c", MINE, "ZONE-C", "申请人甲",
                Instant.parse("2026-09-26T02:00:00Z").toString(), new BigDecimal("150.000")));
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody().get("message").asText()).contains("120");
    }
}
