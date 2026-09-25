package com.example.starter.fermentation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工艺判定口径边界单元测试：恰 0.5℃ 合格、恰 0.10mg/L 合格、恰 12 小时、恰 7 天合法。
 */
class DomainRulesTest {

    @Test
    void temperatureDeviationExactlyHalfDegreeIsInRange() {
        assertThat(DomainRules.isTemperatureOutOfRange(new BigDecimal("10.00"), new BigDecimal("10.50")))
                .isFalse();
        assertThat(DomainRules.isTemperatureOutOfRange(new BigDecimal("10.00"), new BigDecimal("9.50")))
                .isFalse();
    }

    @Test
    void temperatureDeviationBeyondHalfDegreeIsOutOfRange() {
        assertThat(DomainRules.isTemperatureOutOfRange(new BigDecimal("10.00"), new BigDecimal("10.51")))
                .isTrue();
        assertThat(DomainRules.isTemperatureOutOfRange(new BigDecimal("10.00"), new BigDecimal("9.49")))
                .isTrue();
    }

    @Test
    void diacetylExactlyAtLimitPasses() {
        assertThat(DomainRules.isDiacetylPassing(new BigDecimal("0.100"))).isTrue();
        assertThat(DomainRules.isDiacetylPassing(new BigDecimal("0.10"))).isTrue();
        assertThat(DomainRules.isDiacetylPassing(new BigDecimal("0.11"))).isFalse();
    }

    @Test
    void samplingIntervalExactlyTwelveHoursIsLegal() {
        assertThat(DomainRules.meetsSamplingInterval(Duration.ofHours(12))).isTrue();
        assertThat(DomainRules.meetsSamplingInterval(Duration.ofHours(11).plusMinutes(59))).isFalse();
    }

    @Test
    void maturityHoldExactlySevenDaysIsLegal() {
        assertThat(DomainRules.meetsMaturityHold(Duration.ofDays(7))).isTrue();
        assertThat(DomainRules.meetsMaturityHold(Duration.ofDays(7).minusMinutes(1))).isFalse();
    }

    @Test
    void fingerprintIsStableAcrossEquivalentNumericFormsAndChangesWithFields() {
        String canonical = "TEMPERATURE_READING|r1|B1|2026-09-01T00:00:00Z|10.5";
        String sameValue = "TEMPERATURE_READING|r1|B1|2026-09-01T00:00:00Z|"
                + new BigDecimal("10.50").stripTrailingZeros().toPlainString();
        assertThat(DomainRules.sha256(canonical)).isEqualTo(DomainRules.sha256(sameValue));
        String changedValue = "TEMPERATURE_READING|r1|B1|2026-09-01T00:00:00Z|10.6";
        assertThat(DomainRules.sha256(canonical)).isNotEqualTo(DomainRules.sha256(changedValue));
    }
}
