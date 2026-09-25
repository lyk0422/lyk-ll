package com.example.starter.wb.calc;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.EnvelopePoint;
import com.example.starter.wb.domain.LoadStation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 载重平衡计算：合成机型 B-TEST，验证业载恰等上限、重心恰包线边界、加油联动重心与超限分支。
 */
class WeightBalanceCalculatorTest {

    private static final Aircraft AC = new Aircraft(
            1, "B-TEST", "SYN-J21", 40000, new BigDecimal("700.00"), 55000, 65000, 60000,
            new BigDecimal("625.00"), new BigDecimal("250.00"), new BigDecimal("690.00"),
            List.of(
                    new EnvelopePoint(40000, new BigDecimal("14.0000")),
                    new EnvelopePoint(55000, new BigDecimal("14.0000")),
                    new EnvelopePoint(65000, new BigDecimal("16.0000")),
                    new EnvelopePoint(65000, new BigDecimal("30.0000")),
                    new EnvelopePoint(55000, new BigDecimal("32.0000")),
                    new EnvelopePoint(40000, new BigDecimal("32.0000"))),
            List.of(
                    new LoadStation("PAX_F", new BigDecimal("600.00"), "前舱旅客区"),
                    new LoadStation("PAX_E", new BigDecimal("760.00"), "后舱旅客区"),
                    new LoadStation("CARGO_F", new BigDecimal("500.00"), "前货舱货物"),
                    new LoadStation("MAIL_A", new BigDecimal("800.00"), "后货舱邮件")));

    /** 100 人 @80kg@600in = 8000kg；7000kg 货 @500in；业载恰为 15000 = MZFW-OEW。 */
    private List<CalcLoadItem> exactPayloadItems(int cargoKg) {
        return List.of(
                new CalcLoadItem("PASSENGER", "PAX_F", 100, new BigDecimal("8000.00"), new BigDecimal("600.00")),
                new CalcLoadItem("CARGO", "CARGO_F", 7, new BigDecimal(cargoKg + ".00"), new BigDecimal("500.00")));
    }

    @Test
    void payloadExactlyAtLimitAndZfwCgExactlyOnVertexAreLegal() {
        WeightBalanceResult r = WeightBalanceCalculator.calculate(AC, exactPayloadItems(7000), BigDecimal.ZERO);
        assertThat(r.payloadKg()).isEqualByComparingTo("15000.00");
        assertThat(r.zfwKg()).isEqualByComparingTo("55000");
        assertThat(r.cgZfwPct()).isEqualByComparingTo("14.0000");
        assertThat(r.withinLimits()).isTrue();
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void fuelMovesCgAndTowCgLandsExactlyOnSlopedEdge() {
        // 5000kg@690：TOW=60000，重心 (32.93m+3.45m)/60000 = 606.25in = 恰 15%MAC（斜边）。
        WeightBalanceResult r = WeightBalanceCalculator.calculate(AC, exactPayloadItems(7000), new BigDecimal("5000"));
        assertThat(r.towKg()).isEqualByComparingTo("60000");
        assertThat(r.cgTowPct()).isEqualByComparingTo("15.0000");
        assertThat(r.withinLimits()).isTrue();
        // 油量改变必改变起飞重心，而无油重心保持不变
        WeightBalanceResult moreFuel = WeightBalanceCalculator.calculate(AC, exactPayloadItems(7000), new BigDecimal("6000"));
        assertThat(moreFuel.cgTowPct()).isNotEqualByComparingTo(r.cgTowPct());
        assertThat(moreFuel.cgZfwPct()).isEqualByComparingTo(r.cgZfwPct());
    }

    @Test
    void oneKiloOverPayloadLimitIsRejected() {
        WeightBalanceResult r = WeightBalanceCalculator.calculate(AC, exactPayloadItems(7001), BigDecimal.ZERO);
        assertThat(r.payloadKg()).isEqualByComparingTo("15001.00");
        assertThat(r.withinLimits()).isFalse();
        assertThat(String.join(";", r.violations())).contains("MZFW-OEW");
    }

    @Test
    void aftHeavyCgOutsideEnvelopeIsRejected() {
        // 8000kg 后舱旅客@760 + 7000kg 后货邮件@800：ZFW 55000，重心约 38.58%MAC，超过上界 32%。
        List<CalcLoadItem> aft = List.of(
                new CalcLoadItem("PASSENGER", "PAX_E", 100, new BigDecimal("8000.00"), new BigDecimal("760.00")),
                new CalcLoadItem("MAIL", "MAIL_A", 70, new BigDecimal("7000.00"), new BigDecimal("800.00")));
        WeightBalanceResult r = WeightBalanceCalculator.calculate(AC, aft, BigDecimal.ZERO);
        assertThat(r.withinLimits()).isFalse();
        assertThat(String.join(";", r.violations())).contains("无油重心");
    }

    @Test
    void negativeFuelIsRejectedAndResultsAreRecomputable() {
        List<CalcLoadItem> items = exactPayloadItems(7000);
        WeightBalanceResult first = WeightBalanceCalculator.calculate(AC, items, new BigDecimal("5000"));
        WeightBalanceResult again = WeightBalanceCalculator.calculate(AC, items, new BigDecimal("5000"));
        assertThat(again.cgTowPct()).isEqualTo(first.cgTowPct());
        assertThat(again.payloadKg()).isEqualTo(first.payloadKg());

        WeightBalanceResult neg = WeightBalanceCalculator.calculate(AC, items, new BigDecimal("-1"));
        assertThat(neg.withinLimits()).isFalse();
    }
}
