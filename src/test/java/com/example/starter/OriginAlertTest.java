package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.alert.AlertRecomputeResponse;
import com.example.starter.alert.OriginAlert;
import com.example.starter.domain.SamplingMode;
import com.example.starter.inspection.SampleResponse;
import org.junit.jupiter.api.Test;

/**
 * 产地预警：恰 3 批疑似触发、UTC 日界、预警期间加严抽检、台账复算。
 */
class OriginAlertTest extends AbstractIntegrationTest {

    @Test
    void exactlyThreeSuspectedBatchesTriggerAlert() {
        suspectedBatch("a1", "山东寿光", "番茄");
        suspectedBatch("a2", "山东寿光", "番茄");
        assertThat(originAlertRepository.count()).isZero();

        suspectedBatch("a3", "山东寿光", "番茄");

        assertThat(originAlertRepository.count()).isEqualTo(1);
        OriginAlert alert = originAlertRepository.findAll().get(0);
        assertThat(alert.origin()).isEqualTo("山东寿光");
        assertThat(alert.variety()).isEqualTo("番茄");
        assertThat(alert.alertDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(alert.triggerCount()).isEqualTo(3);
        assertThat(alert.status()).isEqualTo("ACTIVE");

        // 第 4 批疑似不重复登记当日预警
        suspectedBatch("a4", "山东寿光", "番茄");
        assertThat(originAlertRepository.count()).isEqualTo(1);
    }

    @Test
    void alertCountedByUtcDayBoundary() {
        // 日界前 2 批疑似
        clock.reset(Instant.parse("2026-09-26T23:30:00Z"));
        suspectedBatch("d1", "山东寿光", "番茄");
        suspectedBatch("d2", "山东寿光", "番茄");

        // 跨过 UTC 日界后重新累计，第 1 批不触发
        clock.reset(Instant.parse("2026-09-27T00:30:00Z"));
        suspectedBatch("d3", "山东寿光", "番茄");
        assertThat(originAlertRepository.count()).isZero();

        // 当日累计到恰 3 批才触发
        suspectedBatch("d4", "山东寿光", "番茄");
        suspectedBatch("d5", "山东寿光", "番茄");
        assertThat(originAlertRepository.count()).isEqualTo(1);
        assertThat(originAlertRepository.findAll().get(0).alertDate())
                .isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    void enhancedSamplingDuringAlertPeriod() {
        suspectedBatch("e1", "山东寿光", "番茄");
        suspectedBatch("e2", "山东寿光", "番茄");
        suspectedBatch("e3", "山东寿光", "番茄");
        assertThat(originAlertRepository.count()).isEqualTo(1);

        // 预警期间该产地同品种后续批次加严抽检
        long alertOriginBatch = registerBatch("e4-reg", "山东寿光", "番茄").id();
        SampleResponse enhanced = sample("e4-sam", alertOriginBatch, "有机磷");
        assertThat(enhanced.mode()).isEqualTo(SamplingMode.ENHANCED);

        // 其他产地不受影响
        long otherBatch = registerBatch("e5-reg", "云南昆明", "番茄").id();
        SampleResponse normal = sample("e5-sam", otherBatch, "有机磷");
        assertThat(normal.mode()).isEqualTo(SamplingMode.NORMAL);

        // 跨 UTC 日后预警期结束，恢复常规抽检
        clock.plus(Duration.ofDays(1));
        long nextDayBatch = registerBatch("e6-reg", "山东寿光", "番茄").id();
        SampleResponse nextDay = sample("e6-sam", nextDayBatch, "有机磷");
        assertThat(nextDay.mode()).isEqualTo(SamplingMode.NORMAL);
    }

    @Test
    void alertLedgerRecomputableFromTestRecords() {
        suspectedBatch("r1", "山东寿光", "番茄");
        suspectedBatch("r2", "山东寿光", "番茄");
        suspectedBatch("r3", "山东寿光", "番茄");

        AlertRecomputeResponse recompute = alertService.recompute(
                "山东寿光", "番茄", LocalDate.of(2026, 9, 26));

        assertThat(recompute.suspectedCount()).isEqualTo(3);
        assertThat(recompute.threshold()).isEqualTo(3);
        assertThat(recompute.shouldAlert()).isTrue();
        assertThat(recompute.alertExists()).isTrue();

        AlertRecomputeResponse otherDay = alertService.recompute(
                "山东寿光", "番茄", LocalDate.of(2026, 9, 27));
        assertThat(otherDay.suspectedCount()).isZero();
        assertThat(otherDay.shouldAlert()).isFalse();
        assertThat(otherDay.alertExists()).isFalse();
    }
}
