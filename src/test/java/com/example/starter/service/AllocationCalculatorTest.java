package com.example.starter.service;

import java.util.List;

import com.example.starter.service.AllocationCalculator.Allocation;
import com.example.starter.service.AllocationCalculator.Result;
import com.example.starter.service.AllocationCalculator.Unit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限电分摊计算器单测：恰等目标不降机、15% 保留边界、尾差归属、取整合计。
 */
class AllocationCalculatorTest {

    @Test
    void exactTargetNoReduction() {
        // 全场可用出力恰等于目标：不降机
        List<Unit> units = List.of(
                new Unit(1, 1000, 150),
                new Unit(2, 800, 150),
                new Unit(3, 600, 150));
        Result result = AllocationCalculator.allocate(units, 2400);
        assertFalse(result.curtailed());
        assertEquals(0, result.reductionKw());
        assertEquals(2400, result.totalAvailableKw());
        assertEquals(List.of(1000, 800, 600),
                result.allocations().stream().map(Allocation::targetKw).toList());
    }

    @Test
    void targetAboveAvailableNoReduction() {
        List<Unit> units = List.of(new Unit(1, 500, 150), new Unit(2, 500, 150));
        Result result = AllocationCalculator.allocate(units, 1200);
        assertFalse(result.curtailed());
        assertEquals(List.of(500, 500),
                result.allocations().stream().map(Allocation::targetKw).toList());
    }

    @Test
    void proportionalSplitWithTailToMaxHeadroom() {
        // 可用 1000/800/600，最低各 150，目标 2000：差额 400 按可降空间 850/650/450 比例分摊
        List<Unit> units = List.of(
                new Unit(1, 1000, 150),
                new Unit(2, 800, 150),
                new Unit(3, 600, 150));
        Result result = AllocationCalculator.allocate(units, 2000);
        assertTrue(result.curtailed());
        assertEquals(400, result.reductionKw());
        // floor(400*850/1950)=174 → 826；floor(400*650/1950)=133 → 667；floor(400*450/1950)=92 → 508
        // 合计 2001，尾差 1 归入可降空间最大的机组 1 → 825
        assertEquals(List.of(825, 667, 508),
                result.allocations().stream().map(Allocation::targetKw).toList());
        assertEquals(2000,
                result.allocations().stream().mapToInt(Allocation::targetKw).sum());
    }

    @Test
    void tailTieBreaksToSmallestId() {
        // 可降空间并列时尾差归入 id 最小机组
        List<Unit> units = List.of(
                new Unit(1, 1000, 150),
                new Unit(2, 1000, 150));
        Result result = AllocationCalculator.allocate(units, 1999);
        assertEquals(1999,
                result.allocations().stream().mapToInt(Allocation::targetKw).sum());
        // floor(1*850/1700)=0 → 各 1000，尾差 1 归入机组 1
        assertEquals(List.of(999, 1000),
                result.allocations().stream().map(Allocation::targetKw).toList());
    }

    @Test
    void exactlyAtFifteenPercentFloorIsLegal() {
        // 恰降至 15% 技术出力合法
        List<Unit> units = List.of(new Unit(1, 1000, 150));
        Result result = AllocationCalculator.allocate(units, 150);
        assertTrue(result.curtailed());
        assertEquals(150, result.allocations().get(0).targetKw());
    }

    @Test
    void belowFifteenPercentFloorRejected() {
        List<Unit> units = List.of(new Unit(1, 1000, 150));
        assertThrows(IllegalArgumentException.class,
                () -> AllocationCalculator.allocate(units, 149));
    }

    @Test
    void sumAlwaysEqualsTargetAcrossRandomishCases() {
        List<Unit> units = List.of(
                new Unit(1, 1731, 300),
                new Unit(2, 962, 225),
                new Unit(3, 455, 225),
                new Unit(4, 1280, 300));
        for (int target = 1050; target <= 4428; target += 137) {
            Result result = AllocationCalculator.allocate(units, target);
            int sum = result.allocations().stream().mapToInt(Allocation::targetKw).sum();
            if (target >= 4428) {
                assertEquals(4428, sum);
            } else {
                assertEquals(target, sum, "target=" + target);
            }
            for (Allocation a : result.allocations()) {
                assertTrue(a.targetKw() >= a.minKw(), "target=" + target + " " + a);
                assertTrue(a.targetKw() <= a.availableKw(), "target=" + target + " " + a);
            }
        }
    }
}
