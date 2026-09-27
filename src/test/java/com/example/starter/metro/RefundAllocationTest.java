package com.example.starter.metro;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.metro.RefundService.LineAllocationCents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款分摊算法与结构化指纹的纯单元测试（不依赖数据库）。
 */
class RefundAllocationTest {

    /** shares: lineCode -> [原实收份额, 累计已退]。 */
    private Map<String, long[]> shares(long l1, long l2) {
        Map<String, long[]> map = new LinkedHashMap<>();
        map.put("L1", new long[] {l1, 0L});
        map.put("L2", new long[] {l2, 0L});
        return map;
    }

    private Map<String, Long> toMap(List<LineAllocationCents> allocations) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (LineAllocationCents a : allocations) {
            map.put(a.lineCode(), a.amountCents());
        }
        return map;
    }

    @Test
    void floorThenRemainderGoesToLargerShareFirst() {
        // 原份额 L1=188 L2=312，退 100：floor 37+62=99，余 1 分给份额更大的 L2
        Map<String, Long> result = toMap(RefundService.allocateRefund(shares(188, 312), 100));
        assertEquals(37L, result.get("L1"));
        assertEquals(63L, result.get("L2"));
    }

    @Test
    void fullRefundExactlyFillsEveryLine() {
        Map<String, Long> result = toMap(RefundService.allocateRefund(shares(188, 312), 500));
        assertEquals(188L, result.get("L1"));
        assertEquals(312L, result.get("L2"));
    }

    @Test
    void allocationIsCappedByRemainingBalanceAndRemainderCycles() {
        // 首退 400：floor 150+249=399，余 1 -> L2，得 L1=150 L2=250
        Map<String, long[]> afterFirst = shares(188, 312);
        Map<String, Long> first = toMap(RefundService.allocateRefund(afterFirst, 400));
        assertEquals(150L, first.get("L1"));
        assertEquals(250L, first.get("L2"));
        afterFirst.get("L1")[1] = 150;
        afterFirst.get("L2")[1] = 250;

        // 再退 100：floor 37+62=99，L2 恰只剩 62，余 1 循环给仍有余额的 L1
        Map<String, Long> second = toMap(RefundService.allocateRefund(afterFirst, 100));
        assertEquals(38L, second.get("L1"));
        assertEquals(62L, second.get("L2"));
        // 恰退满：各线路累计等于原份额
        assertEquals(188L, afterFirst.get("L1")[1] + second.get("L1"));
        assertEquals(312L, afterFirst.get("L2")[1] + second.get("L2"));
    }

    @Test
    void tieBreaksByShareDescThenLineCodeAsc() {
        // 同份额 L2 与 L1，余分按线路编号升序：L1 先得
        Map<String, long[]> map = new LinkedHashMap<>();
        map.put("L2", new long[] {100, 0});
        map.put("L1", new long[] {100, 0});
        List<LineAllocationCents> result = RefundService.allocateRefund(map, 3);
        // 顺序：份额相同按编号升序 -> L1, L2
        assertEquals("L1", result.get(0).lineCode());
        assertEquals(2L, result.get(0).amountCents());
        assertEquals(1L, result.get(1).amountCents());
    }

    @Test
    void zeroNegativeAndExceedingRefundAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RefundService.allocateRefund(shares(188, 312), 0));
        assertThrows(IllegalArgumentException.class,
                () -> RefundService.allocateRefund(shares(188, 312), -1));
        Map<String, long[]> map = shares(188, 312);
        map.get("L1")[1] = 188;
        map.get("L2")[1] = 312;
        assertThrows(IllegalArgumentException.class,
                () -> RefundService.allocateRefund(map, 1));
    }

    @Test
    void everyAllocationSumsExactlyToRefundAndNeverExceedsShare() {
        int[] refunds = {1, 7, 99, 100, 250, 499, 500};
        for (int refund : refunds) {
            Map<String, long[]> map = shares(188, 312);
            List<LineAllocationCents> result = RefundService.allocateRefund(map, refund);
            long sum = result.stream().mapToLong(LineAllocationCents::amountCents).sum();
            assertEquals(refund, sum, "分摊合计必须等于退款");
            for (LineAllocationCents a : result) {
                long original = map.get(a.lineCode())[0];
                assertTrue(a.amountCents() <= original, "单线路分摊不超原份额");
                assertTrue(a.amountCents() >= 0, "分摊金额不为负");
            }
        }
    }

    @Test
    void structuredFingerprintDistinguishesDelimiterInsideText() {
        // ["a|b","c"] 与 ["a","b|c"] 用朴素 '|' 拼接无法区分；长度前缀编码必须区分
        String fp1 = IdempotencyExecutor.fingerprint("S", "a|b", "c");
        String fp2 = IdempotencyExecutor.fingerprint("S", "a", "b|c");
        assertNotEquals(fp1, fp2);
        // 同参同键指纹稳定
        assertEquals(fp1, IdempotencyExecutor.fingerprint("S", "a|b", "c"));
        // 不同 scope 区分
        assertNotEquals(fp1, IdempotencyExecutor.fingerprint("T", "a|b", "c"));
        // null 与字面量 "-1" 文本区分
        assertNotEquals(IdempotencyExecutor.fingerprint("S", (String) null),
                IdempotencyExecutor.fingerprint("S", "#-1:"));
    }
}
