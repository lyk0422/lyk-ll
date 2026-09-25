package com.example.starter.wbb.service;

import com.example.starter.wbb.dto.PayloadItemDto;
import com.example.starter.wbb.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 载重平衡纯计算单测：恰达重量上限、恰在包线边界、LMC 恰达限额。
 */
class BalanceCalculatorTest {

    private static final BigDecimal OEW = new BigDecimal("40000");
    private static final BigDecimal MZFW = new BigDecimal("60000");

    private List<BalanceCalculator.EnvelopePoint> envelope() {
        return List.of(
                new BalanceCalculator.EnvelopePoint(new BigDecimal("40000"),
                        new BigDecimal("15.5"), new BigDecimal("16.5")),
                new BalanceCalculator.EnvelopePoint(new BigDecimal("60000"),
                        new BigDecimal("15.6"), new BigDecimal("16.4")),
                new BalanceCalculator.EnvelopePoint(new BigDecimal("75000"),
                        new BigDecimal("15.7"), new BigDecimal("16.3")));
    }

    @Test
    void payloadExactlyAtMaxZeroFuelLimitIsLegal() {
        // 最大业载 = 60000 - 40000 = 20000，恰等合法。
        assertThat(BalanceCalculator.withinZeroFuelLimit(new BigDecimal("20000"), OEW, MZFW)).isTrue();
        assertThat(BalanceCalculator.withinZeroFuelLimit(new BigDecimal("20000.01"), OEW, MZFW)).isFalse();
    }

    @Test
    void sumPayloadRecomputesWeightMomentAndPax() {
        PayloadItemDto pax = new PayloadItemDto("PAX", "FWD", 10, new BigDecimal("800"),
                new BigDecimal("15.0"));
        PayloadItemDto cargo = new PayloadItemDto("CARGO", "AFT", 3, new BigDecimal("200"),
                new BigDecimal("17.0"));
        BalanceCalculator.PayloadTotals totals = BalanceCalculator.sumPayload(List.of(pax, cargo));
        assertThat(totals.weightKg()).isEqualByComparingTo("1000");
        // 800*15 + 200*17 = 12000 + 3400 = 15400 kg·m
        assertThat(totals.moment()).isEqualByComparingTo("15400.00");
        assertThat(totals.passengerCount()).isEqualTo(10);
    }

    @Test
    void cgExactlyOnEnvelopeBoundaryIsLegal() {
        // ZFW=60000 折点前限恰为 15.6：936000/60000 = 15.6，恰在边界合法。
        BigDecimal arm = BalanceCalculator.cgArm(new BigDecimal("936000"), new BigDecimal("60000"));
        assertThat(arm).isEqualByComparingTo("15.600000");
        assertThat(BalanceCalculator.withinEnvelope(new BigDecimal("60000"), arm, envelope())).isTrue();
        // 后限恰为 16.4 同样合法。
        BigDecimal aft = BalanceCalculator.cgArm(new BigDecimal("984000"), new BigDecimal("60000"));
        assertThat(BalanceCalculator.withinEnvelope(new BigDecimal("60000"), aft, envelope())).isTrue();
    }

    @Test
    void cgOutsideEnvelopeIsRejected() {
        BigDecimal tooFwd = new BigDecimal("15.599000");
        assertThat(BalanceCalculator.withinEnvelope(new BigDecimal("60000"), tooFwd, envelope())).isFalse();
        // 重量超出折点区间不外推。
        assertThat(BalanceCalculator.withinEnvelope(new BigDecimal("76000"), new BigDecimal("16.0"),
                envelope())).isFalse();
    }

    @Test
    void interpolatedBoundaryUsesLinearLimits() {
        // 重量 65000 位于两折点中间：前限 15.633333、后限 16.366667。
        BigDecimal w = new BigDecimal("65000");
        BigDecimal moment = w.multiply(new BigDecimal("15.633333"));
        BigDecimal arm = BalanceCalculator.cgArm(moment, w);
        assertThat(BalanceCalculator.withinEnvelope(w, arm, envelope())).isTrue();
        assertThat(BalanceCalculator.withinEnvelope(w, new BigDecimal("15.630000"), envelope())).isFalse();
    }

    @Test
    void lmcExactlyAtLimitIsIncluded() {
        // 恰达 ±5 人 / ±500kg 合法。
        assertThat(BalanceCalculator.withinLmcLimit(5, new BigDecimal("500"))).isTrue();
        assertThat(BalanceCalculator.withinLmcLimit(-5, new BigDecimal("-500"))).isTrue();
        assertThat(BalanceCalculator.withinLmcLimit(6, new BigDecimal("480"))).isFalse();
        assertThat(BalanceCalculator.withinLmcLimit(4, new BigDecimal("500.01"))).isFalse();
    }

    @Test
    void zeroWeightCgHasNoArm() {
        assertThatThrownBy(() -> BalanceCalculator.cgArm(BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(BusinessRuleException.class);
    }
}
