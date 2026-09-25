package com.example.starter.irrigation.service;

import com.example.starter.irrigation.service.AllocationCalculator.Allocation;
import com.example.starter.irrigation.service.AllocationCalculator.DeficitOutlet;
import com.example.starter.irrigation.service.AllocationCalculator.WeightedOutlet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配水分配与欠供结转纯函数测试：权重四位小数、取整差额归属、30% 结转边界、加购按欠供比例。
 */
class AllocationCalculatorTest {

    private static WeightedOutlet outlet(long id, String code, String area) {
        return new WeightedOutlet(id, code, new BigDecimal(area));
    }

    @Test
    void 按面积权重分配_权重四位小数_合计等于可供总量() {
        List<Allocation> result = AllocationCalculator.allocateByArea(10000, List.of(
                outlet(1, "D1", "100"), outlet(2, "D2", "200"), outlet(3, "D3", "300")));
        // 权重 0.1667 / 0.3333 / 0.5000 → 1667 / 3333 / 5000，合计恰为 10000。
        assertEquals(1667, result.get(0).amountM3());
        assertEquals(3333, result.get(1).amountM3());
        assertEquals(5000, result.get(2).amountM3());
        assertEquals(10000, result.stream().mapToLong(Allocation::amountM3).sum());
    }

    @Test
    void 取整差额归入面积最大的斗口() {
        // 权重 0.1667/0.3333/0.5000，总量 5 → 1/2/3（0.8335→1、1.6665→2、2.5→3），
        // 合计 6 超出 1，差额 -1 归入面积最大的 D3。
        List<Allocation> result = AllocationCalculator.allocateByArea(5, List.of(
                outlet(1, "D1", "100"), outlet(2, "D2", "200"), outlet(3, "D3", "300")));
        assertEquals(1, result.get(0).amountM3());
        assertEquals(2, result.get(1).amountM3());
        assertEquals(2, result.get(2).amountM3());
        assertEquals(5, result.stream().mapToLong(Allocation::amountM3).sum());
    }

    @Test
    void 面积并列时差额归入斗口编号最小者() {
        // 面积相等，权重各 0.3333，总量 10000 → 各 3333，合计 9999，差额 +1 归入 D1。
        List<Allocation> result = AllocationCalculator.allocateByArea(10000, List.of(
                outlet(1, "D1", "50"), outlet(2, "D2", "50"), outlet(3, "D3", "50")));
        assertEquals(3334, result.get(0).amountM3());
        assertEquals(3333, result.get(1).amountM3());
        assertEquals(3333, result.get(2).amountM3());
        assertEquals(10000, result.stream().mapToLong(Allocation::amountM3).sum());
    }

    @Test
    void 空斗口或零面积拒绝分配() {
        assertThrows(IllegalArgumentException.class,
                () -> AllocationCalculator.allocateByArea(100, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AllocationCalculator.allocateByArea(100, List.of(outlet(1, "D1", "0"))));
    }

    @Test
    void 结转恰满百分之三十允许() {
        assertEquals(300, AllocationCalculator.carryover(1000, 300));
    }

    @Test
    void 结转超过百分之三十的部分作废() {
        assertEquals(300, AllocationCalculator.carryover(1000, 301));
        assertEquals(300, AllocationCalculator.carryover(1000, 5000));
    }

    @Test
    void 结转上限按非整数百搭向下取整() {
        // 计划 1033，30% = 309.9：欠供 309 全额结转，欠供 310 只能结转 309。
        assertEquals(309, AllocationCalculator.carryover(1033, 309));
        assertEquals(309, AllocationCalculator.carryover(1033, 310));
        assertEquals(0, AllocationCalculator.carryover(0, 5));
        assertEquals(0, AllocationCalculator.carryover(1000, 0));
    }

    @Test
    void 加购按欠供比例分配且仅欠供斗口参与() {
        List<Allocation> result = AllocationCalculator.allocateByDeficit(100, List.of(
                new DeficitOutlet(1, "D1", 0),
                new DeficitOutlet(2, "D2", 333),
                new DeficitOutlet(3, "D3", 2000)));
        // 100*333/2333=14.27→14，100*2000/2333=85.73→86；无欠供的 D1 不参与。
        assertEquals(2, result.size());
        assertEquals(14, result.get(0).amountM3());
        assertEquals(86, result.get(1).amountM3());
        assertEquals(100, result.stream().mapToLong(Allocation::amountM3).sum());
    }

    @Test
    void 加购差额归入欠供最大的斗口() {
        List<Allocation> result = AllocationCalculator.allocateByDeficit(10, List.of(
                new DeficitOutlet(1, "D1", 1),
                new DeficitOutlet(2, "D2", 1),
                new DeficitOutlet(3, "D3", 1)));
        // 各 3.33→3，合计 9，差额 +1 归入欠供并列最大中编号最小的 D1。
        assertEquals(4, result.get(0).amountM3());
        assertEquals(3, result.get(1).amountM3());
        assertEquals(3, result.get(2).amountM3());
        assertEquals(10, result.stream().mapToLong(Allocation::amountM3).sum());
    }

    @Test
    void 无欠供斗口时拒绝加购分配() {
        assertThrows(IllegalArgumentException.class, () ->
                AllocationCalculator.allocateByDeficit(100, List.of(new DeficitOutlet(1, "D1", 0))));
    }
}
