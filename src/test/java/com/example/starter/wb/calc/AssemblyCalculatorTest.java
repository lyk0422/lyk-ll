package com.example.starter.wb.calc;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.EnvelopePoint;
import com.example.starter.wb.domain.LoadStation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集装器装配模式计算：货运力矩按总重×整数毫米力臂精确求和（kg·mm），
 * 与站位英寸路径数学等价；超限仍由既有重心包线规则判定。
 */
class AssemblyCalculatorTest {

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

    private static List<CalcLoadItem> paxOnly() {
        return List.of(new CalcLoadItem("PASSENGER", "PAX_F", 100,
                new BigDecimal("8000.00"), new BigDecimal("600.00")));
    }

    @Test
    void assemblyMomentIsExactIntegerKgMmAndMatchesStationPath() {
        // 7000kg 货 @500in 站位路径 与 7000×12700mm=88,900,000 kg·mm 装配路径：重心完全一致。
        List<CalcLoadItem> stationPath = List.of(
                new CalcLoadItem("PASSENGER", "PAX_F", 100, new BigDecimal("8000.00"), new BigDecimal("600.00")),
                new CalcLoadItem("CARGO", "CARGO_F", 7, new BigDecimal("7000.00"), new BigDecimal("500.00")));
        WeightBalanceResult station = WeightBalanceCalculator.calculate(AC, stationPath, new BigDecimal("5000"));
        assertThat(station.cgZfwPct()).isEqualByComparingTo("14.0000");
        assertThat(station.cgTowPct()).isEqualByComparingTo("15.0000");

        WeightBalanceResult assembly = WeightBalanceCalculator.calculateWithCargoMoment(
                AC, paxOnly(), new BigDecimal("7000"), new BigDecimal("88900000"), new BigDecimal("5000"));
        assertThat(assembly.cgZfwPct()).isEqualByComparingTo("14.0000");
        assertThat(assembly.cgTowPct()).isEqualByComparingTo("15.0000");
        assertThat(assembly.cgZfwPct()).isEqualByComparingTo(station.cgZfwPct());
        assertThat(assembly.cgTowPct()).isEqualByComparingTo(station.cgTowPct());
        // 力矩精确求和：OEW 711,200,000 + 旅客 121,920,000 + 货运 88,900,000 = 922,020,000 kg·mm
        assertThat(assembly.zfwMoment()).isEqualByComparingTo("922020000.00");
        assertThat(assembly.withinLimits()).isTrue();
    }

    @Test
    void twoUldMomentSumIsExact() {
        // ULD001 3000kg@13000mm + ULD002 3000kg@15000mm = 39,000,000 + 45,000,000 = 84,000,000 kg·mm
        BigDecimal moment = BigDecimal.valueOf(3000L * 13000L).add(BigDecimal.valueOf(3000L * 15000L));
        WeightBalanceResult r = WeightBalanceCalculator.calculateWithCargoMoment(
                AC, List.of(new CalcLoadItem("PASSENGER", "PAX_F", 90,
                        new BigDecimal("7200.00"), new BigDecimal("600.00"))),
                new BigDecimal("6000"), moment, new BigDecimal("5000"));
        assertThat(r.zfwMoment()).isEqualByComparingTo("904928000.00");
        assertThat(r.cgZfwPct()).isEqualByComparingTo("17.8728");
        assertThat(r.cgTowPct()).isEqualByComparingTo("18.5710");
        assertThat(r.withinLimits()).isTrue();
    }

    @Test
    void aftAssemblyMomentOutsideEnvelopeIsRejectedByExistingRules() {
        // 7000kg 全部压在后舱 19000mm：重心约 35.94%MAC，超过包线上界 32%，整版拒绝。
        WeightBalanceResult r = WeightBalanceCalculator.calculateWithCargoMoment(
                AC, List.of(new CalcLoadItem("PASSENGER", "PAX_E", 100,
                        new BigDecimal("8000.00"), new BigDecimal("760.00"))),
                new BigDecimal("7000"), new BigDecimal("133000000"), BigDecimal.ZERO);
        assertThat(r.withinLimits()).isFalse();
        assertThat(String.join(";", r.violations())).contains("无油重心");
    }
}
