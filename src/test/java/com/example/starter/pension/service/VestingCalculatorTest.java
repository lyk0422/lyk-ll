package com.example.starter.pension.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 司龄整年与企业缴费归属档位的单元测试，重点覆盖“恰满档位年限按该档归属”。
 */
class VestingCalculatorTest {

    private final VestingCalculator calculator = new VestingCalculator();

    @DisplayName("入职日到申请日的整年计算与档位比例边界")
    @ParameterizedTest(name = "{0} 入职、{1} 申请 -> {2} 年, {3} bp")
    @CsvSource({
            // 恰满 3/5/8 年，按该档归属
            "2023-09-25,2026-09-25,3,5000",
            "2021-09-25,2026-09-25,5,8000",
            "2018-09-25,2026-09-25,8,10000",
            // 差一天不满 3 年 -> 0%
            "2023-09-26,2026-09-25,2,0",
            // 满 3 后差一天不满 5 -> 仍 50%
            "2021-09-26,2026-09-25,4,5000",
            // 满 5 后差一天不满 8 -> 仍 80%
            "2018-09-26,2026-09-25,7,8000",
            // 超过 8 年 -> 100%
            "2010-01-01,2026-09-25,16,10000",
            // 闰年 2 月 29 入职
            "2024-02-29,2026-02-28,1,0"
    })
    void hireYearsAndRatio(LocalDate hireDate, LocalDate requestDate,
                           int expectedYears, int expectedRatioBp) {
        int years = calculator.hireYears(hireDate, requestDate);
        assertThat(years).isEqualTo(expectedYears);
        assertThat(calculator.vestedRatioBp(years)).isEqualTo(expectedRatioBp);
    }

    @Test
    @DisplayName("申请日早于入职日时司龄按 0 处理")
    void requestBeforeHire() {
        assertThat(calculator.hireYears(LocalDate.of(2030, 1, 1), LocalDate.of(2026, 1, 1)))
                .isZero();
        assertThat(calculator.vestedRatioBp(0)).isZero();
    }

    @Test
    @DisplayName("企业缴费归属金额精确到分，不足 1 分舍去")
    void vestedEnterpriseRoundsDownToCent() {
        // 10001 分的 50% = 5000.5 分 -> 5000
        assertThat(calculator.vestedEnterprise(10001L, VestingCalculator.RATIO_50_BP))
                .isEqualTo(5000L);
        // 80%
        assertThat(calculator.vestedEnterprise(10001L, VestingCalculator.RATIO_80_BP))
                .isEqualTo(8000L);
        // 100% 全额
        assertThat(calculator.vestedEnterprise(10001L, VestingCalculator.RATIO_100_BP))
                .isEqualTo(10001L);
        // 0% 不归
        assertThat(calculator.vestedEnterprise(99999L, 0)).isZero();
    }
}
