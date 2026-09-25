package com.example.starter.irrigation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllocationCalculatorTest {

    @Test
    void allocateByArea_weightsKeepFourDecimalsAndSumEqualsTotal() {
        // 总面积 1000，总量 100 m3：比例 60/30/10，天然整除
        List<AllocationCalculator.Allocation> result = AllocationCalculator.allocateByArea(
                List.of(
                        new AllocationCalculator.OutletArea("D001", 600),
                        new AllocationCalculator.OutletArea("D002", 300),
                        new AllocationCalculator.OutletArea("D003", 100)),
                100);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(AllocationCalculator.Allocation::weight)
                .containsExactly(new BigDecimal("0.6000"), new BigDecimal("0.3000"), new BigDecimal("0.1000"));
        assertThat(result).extracting(AllocationCalculator.Allocation::planCubicMeter)
                .containsExactly(60L, 30L, 10L);
        assertThat(result.stream().mapToLong(AllocationCalculator.Allocation::planCubicMeter).sum())
                .isEqualTo(100L);
    }

    @Test
    void allocateByArea_remainderGoesToLargestAreaOutlet() {
        // 面积 1/1/1，总量 10 m3：权重各 0.3333，floor 后各 3，差额 1 归入面积最大者；
        // 面积并列时归入斗口编号较小者 D001。
        List<AllocationCalculator.Allocation> result = AllocationCalculator.allocateByArea(
                List.of(
                        new AllocationCalculator.OutletArea("D003", 1),
                        new AllocationCalculator.OutletArea("D001", 1),
                        new AllocationCalculator.OutletArea("D002", 1)),
                10);

        assertThat(result).isSortedAccordingTo(java.util.Comparator.comparing(
                AllocationCalculator.Allocation::outletCode));
        assertThat(result).extracting(AllocationCalculator.Allocation::planCubicMeter)
                .containsExactly(4L, 3L, 3L);
        assertThat(result.stream().mapToLong(AllocationCalculator.Allocation::planCubicMeter).sum())
                .isEqualTo(10L);
    }

    @Test
    void allocateByArea_uniqueLargestAreaOutletTakesRemainder() {
        // 面积 7/2/1，总量 100：权重 0.7/0.2/0.1，floor 后合计 100，无差额
        List<AllocationCalculator.Allocation> exact = AllocationCalculator.allocateByArea(
                List.of(
                        new AllocationCalculator.OutletArea("D001", 7),
                        new AllocationCalculator.OutletArea("D002", 2),
                        new AllocationCalculator.OutletArea("D003", 1)),
                10);
        assertThat(exact).extracting(AllocationCalculator.Allocation::planCubicMeter)
                .containsExactly(7L, 2L, 1L);

        // 面积 8/1/1，总量 10：权重 0.8/0.1/0.1，floor 后 8+1+1=10 无差额
        // 面积 3/3/4（总面积 10），总量 7：权重 0.3/0.3/0.4，
        // floor 后 2+2+2=6，差额 1 归入面积最大的 D003
        List<AllocationCalculator.Allocation> withRemainderToUniqueMax = AllocationCalculator.allocateByArea(
                List.of(
                        new AllocationCalculator.OutletArea("D001", 3),
                        new AllocationCalculator.OutletArea("D002", 3),
                        new AllocationCalculator.OutletArea("D003", 4)),
                7);
        assertThat(withRemainderToUniqueMax).extracting(a -> a.outletCode() + "=" + a.planCubicMeter())
                .containsExactly("D001=2", "D002=2", "D003=3");
        assertThat(withRemainderToUniqueMax.stream()
                .mapToLong(AllocationCalculator.Allocation::planCubicMeter).sum()).isEqualTo(7L);

        // 构造真实差额场景：面积 2/1/1，总量 10：权重 .5/.25/.25 => 5+2+2=9，
        // 差额 1 归入面积最大的 D001
        List<AllocationCalculator.Allocation> withRemainder = AllocationCalculator.allocateByArea(
                List.of(
                        new AllocationCalculator.OutletArea("D002", 1),
                        new AllocationCalculator.OutletArea("D001", 2),
                        new AllocationCalculator.OutletArea("D003", 1)),
                10);
        assertThat(withRemainder).extracting(a -> a.outletCode() + "=" + a.planCubicMeter())
                .containsExactly("D001=6", "D002=2", "D003=2");
    }

    @Test
    void allocateByArea_zeroTotalGivesZeroPlans() {
        List<AllocationCalculator.Allocation> result = AllocationCalculator.allocateByArea(
                List.of(
                        new AllocationCalculator.OutletArea("D001", 5),
                        new AllocationCalculator.OutletArea("D002", 5)),
                0);
        assertThat(result).extracting(AllocationCalculator.Allocation::planCubicMeter)
                .containsExactly(0L, 0L);
    }

    @Test
    void allocateByArea_rejectsInvalidInput() {
        assertThatThrownBy(() -> AllocationCalculator.allocateByArea(List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationCalculator.allocateByArea(
                List.of(new AllocationCalculator.OutletArea("D001", 0)), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationCalculator.allocateByArea(
                List.of(new AllocationCalculator.OutletArea("D001", 1)), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carryover_exactThirtyPercentAllowed() {
        // 计划 100，欠供 30：恰满 30%，允许全额结转
        assertThat(AllocationCalculator.carryover(100, 30)).isEqualTo(30L);
    }

    @Test
    void carryover_aboveCapIsVoided() {
        // 计划 100，欠供 80：上限 30，超出 50 作废
        assertThat(AllocationCalculator.carryover(100, 80)).isEqualTo(30L);
    }

    @Test
    void carryover_belowCapCarriesFullShortage() {
        assertThat(AllocationCalculator.carryover(100, 20)).isEqualTo(20L);
        assertThat(AllocationCalculator.carryover(100, 0)).isZero();
    }

    @Test
    void carryover_fractionalCapFloorsToCubicMeter() {
        // 计划 11，30% = 3.3，向下取整为 3 立方米
        assertThat(AllocationCalculator.carryover(11, 10)).isEqualTo(3L);
        // 计划 10，30% = 3 整
        assertThat(AllocationCalculator.carryover(10, 10)).isEqualTo(3L);
    }
}
