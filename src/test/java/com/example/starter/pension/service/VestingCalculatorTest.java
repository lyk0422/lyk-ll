package com.example.starter.pension.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VestingCalculator} 纯计算单元测试：司龄整年、恰满档位归属、尾差拆分。
 */
class VestingCalculatorTest {

    private final VestingCalculator calculator = new VestingCalculator();

    @Test
    @DisplayName("司龄按入职日至申请日的整年计算，不满一年为 0")
    void tenureByFullYears() {
        LocalDate hire = LocalDate.of(2020, 3, 15);
        assertThat(calculator.tenureYears(hire, LocalDate.of(2021, 3, 14))).isZero();
        assertThat(calculator.tenureYears(hire, LocalDate.of(2021, 3, 15))).isEqualTo(1);
        assertThat(calculator.tenureYears(hire, LocalDate.of(2028, 3, 14))).isEqualTo(7);
        assertThat(calculator.tenureYears(hire, LocalDate.of(2028, 3, 15))).isEqualTo(8);
    }

    @Test
    @DisplayName("企业缴费归属阶梯：不足3年0%，恰满3年50%，恰满5年80%，恰满8年100%")
    void employerVestingAtExactTierBoundaries() {
        assertThat(calculator.employerVestingRateBp(0)).isZero();
        assertThat(calculator.employerVestingRateBp(2)).isZero();
        assertThat(calculator.employerVestingRateBp(3)).isEqualTo(5_000);
        assertThat(calculator.employerVestingRateBp(4)).isEqualTo(5_000);
        assertThat(calculator.employerVestingRateBp(5)).isEqualTo(8_000);
        assertThat(calculator.employerVestingRateBp(7)).isEqualTo(8_000);
        assertThat(calculator.employerVestingRateBp(8)).isEqualTo(10_000);
        assertThat(calculator.employerVestingRateBp(20)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("归属金额精确到分，向下取整，未归属余数划回企业")
    void vestedAmountRoundsDownToCents() {
        // 10001 分 * 50% = 5000.5 分 -> 5000 分归属，1 分余数与整额一并划回
        assertThat(calculator.vestedAmount(10_001L, 5_000)).isEqualTo(5_000L);
        assertThat(calculator.vestedAmount(10_000L, 5_000)).isEqualTo(5_000L);
        assertThat(calculator.vestedAmount(10_000L, 8_000)).isEqualTo(8_000L);
        assertThat(calculator.vestedAmount(9_999L, 10_000)).isEqualTo(9_999L);
        assertThat(calculator.vestedAmount(9_999L, 0)).isZero();
    }

    @Test
    @DisplayName("分期金额求和等于总额，尾差并入最后一期")
    void splitInstallmentsRemainderGoesToLast() {
        List<Long> twelve = calculator.splitInstallments(10_000L, 12);
        assertThat(twelve).hasSize(12);
        assertThat(twelve.subList(0, 11)).allMatch(v -> v == 833L);
        assertThat(twelve.get(11)).isEqualTo(837L);
        assertThat(twelve).extracting(Long::longValue).hasSize(12);
        assertThat(twelve.stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);

        List<Long> lump = calculator.splitInstallments(5_000L, 1);
        assertThat(lump).containsExactly(5_000L);

        List<Long> tiny = calculator.splitInstallments(3L, 12);
        assertThat(tiny.stream().mapToLong(Long::longValue).sum()).isEqualTo(3L);
        assertThat(tiny.get(11)).isEqualTo(3L);
        assertThat(tiny.subList(0, 11)).allMatch(v -> v == 0L);
    }

    @Test
    @DisplayName("非法期数与负总额抛出异常")
    void invalidSplitArguments() {
        assertThatThrownBy(() -> calculator.splitInstallments(100L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.splitInstallments(-1L, 12))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
