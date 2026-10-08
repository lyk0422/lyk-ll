package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.service.SplitAllocation;

/**
 * 分缸耗用分摊纯单元测试：向下截到 0.0001g + 余量按子缸编号升序循环分配，合计守恒。
 */
class SplitAllocationTest {

    private static final BigDecimal UNIT = new BigDecimal("0.0001");

    private static BigDecimal sum(List<BigDecimal> parts) {
        return parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void split100kg_to40and60_exactProportions() {
        List<BigDecimal> weights = List.of(new BigDecimal("40"), new BigDecimal("60"));
        // 100kg 浴比10 浓度2.0：2000g
        assertEquals(List.of(new BigDecimal("800.0000"), new BigDecimal("1200.0000")),
                SplitAllocation.allocate(new BigDecimal("2000.0000"), weights));
        // 1500g
        assertEquals(List.of(new BigDecimal("600.0000"), new BigDecimal("900.0000")),
                SplitAllocation.allocate(new BigDecimal("1500.0000"), weights));
        // 500g
        assertEquals(List.of(new BigDecimal("200.0000"), new BigDecimal("300.0000")),
                SplitAllocation.allocate(new BigDecimal("500.0000"), weights));
    }

    @Test
    void minimumUnits_floorThenCyclicBySeq_remainderGoesToLowestSeqFirst() {
        List<BigDecimal> weights = List.of(new BigDecimal("40"), new BigDecimal("60"));
        // 总量 0.0003g：floor 后 0.0001/0.0001，余 1 单位给编号1 → 0.0002/0.0001
        List<BigDecimal> parts = SplitAllocation.allocate(new BigDecimal("0.0003"), weights);
        assertEquals(List.of(new BigDecimal("0.0002"), new BigDecimal("0.0001")), parts);
        assertEquals(new BigDecimal("0.0003"), sum(parts));
    }

    @Test
    void singleUnitAcrossThreeChildren_onlySeq1Receives() {
        List<BigDecimal> weights = List.of(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"));
        List<BigDecimal> parts = SplitAllocation.allocate(UNIT, weights);
        assertEquals(List.of(UNIT, new BigDecimal("0.0000"), new BigDecimal("0.0000")), parts);
        assertEquals(UNIT, sum(parts));
    }

    @Test
    void cyclicRemainder_wrapsBySeqAscending() {
        List<BigDecimal> weights = List.of(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"));
        // 0.0005g 三等分：每子缸 floor 0.0001，余 2 单位 → 编号1、编号2
        List<BigDecimal> parts = SplitAllocation.allocate(new BigDecimal("0.0005"), weights);
        assertEquals(List.of(new BigDecimal("0.0002"), new BigDecimal("0.0002"), new BigDecimal("0.0001")),
                parts);
        assertEquals(new BigDecimal("0.0005"), sum(parts));

        // 4 缸等分 0.0010g：每缸 floor 0.0002，余 2 单位 → 编号1、编号2
        List<BigDecimal> four = List.of(new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"));
        assertEquals(List.of(new BigDecimal("0.0003"), new BigDecimal("0.0003"),
                        new BigDecimal("0.0002"), new BigDecimal("0.0002")),
                SplitAllocation.allocate(new BigDecimal("0.0010"), four));
    }

    @Test
    void awkwardWeights_alwaysConservesAndUnitsAreIntegral() {
        List<BigDecimal> weights = List.of(new BigDecimal("33.333"), new BigDecimal("33.333"),
                new BigDecimal("33.334"));
        for (String total : List.of("1.0000", "0.0007", "123.4567", "9999.9999")) {
            List<BigDecimal> parts = SplitAllocation.allocate(new BigDecimal(total), weights);
            assertEquals(new BigDecimal(total), sum(parts), "分摊合计必须等于来源实际耗用: " + total);
            for (BigDecimal p : parts) {
                assertEquals(0, p.remainder(UNIT).compareTo(BigDecimal.ZERO),
                        "每个子缸耗用必须是0.0001g整数倍: " + p);
            }
        }
    }

    @Test
    void rejectsNonIntegralUnitTotalAndNonPositiveWeight() {
        assertThrows(IllegalArgumentException.class,
                () -> SplitAllocation.allocate(new BigDecimal("0.00005"),
                        List.of(new BigDecimal("1"), new BigDecimal("1"))));
        assertThrows(IllegalArgumentException.class,
                () -> SplitAllocation.allocate(new BigDecimal("1.0000"),
                        List.of(new BigDecimal("0"), new BigDecimal("1"))));
        assertThrows(IllegalArgumentException.class,
                () -> SplitAllocation.allocate(new BigDecimal("1.0000"), List.of()));
    }
}
