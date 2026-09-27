package com.example.starter.metro;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 退款按原线路份额分摊规则与结构化指纹无歧义性的纯单元测试（不依赖数据库）。
 */
class RefundAllocationTest {

    private long sum(Map<String, Long> allocation) {
        return allocation.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void floorThenRemainderFollowsShareDescLineAsc() {
        // 合成 A->F：L1 188、L2 312，退 300
        Map<String, Long> result = RefundService.allocate(
                Map.of("L1", 188L, "L2", 312L), Map.of(), 300);
        assertEquals(112L, result.get("L1"));
        assertEquals(188L, result.get("L2"));
        assertEquals(300L, sum(result));
    }

    @Test
    void equalSharesTieBreakByLineCodeAscending() {
        Map<String, Long> result = RefundService.allocate(
                Map.of("L1", 100L, "L2", 100L, "L3", 100L), Map.of(), 100);
        assertEquals(34L, result.get("L1"), "剩余 1 分按线路编号升序给 L1");
        assertEquals(33L, result.get("L2"));
        assertEquals(33L, result.get("L3"));
        assertEquals(100L, sum(result));
    }

    @Test
    void remainderCyclesMultipleRounds() {
        // 三份相等份额，退 5：各先 floor(5*2/6)=1，余 2 依次补给 L1、L2
        Map<String, Long> result = RefundService.allocate(
                Map.of("L1", 2L, "L2", 2L, "L3", 2L), Map.of(), 5);
        assertEquals(2L, result.get("L1"));
        assertEquals(2L, result.get("L2"));
        assertEquals(1L, result.get("L3"));
        assertEquals(5L, sum(result));
    }

    @Test
    void allocationIsClampedToPerLineRefundableBalance() {
        // L2 原份额 99 已退 98，仅剩 1；L1 剩 1。退 2 不得突破各线路可退余额
        Map<String, Long> result = RefundService.allocate(
                Map.of("L1", 1L, "L2", 99L), Map.of("L2", 98L), 2);
        assertEquals(1L, result.get("L1"));
        assertEquals(1L, result.get("L2"));
        assertEquals(2L, sum(result));
    }

    @Test
    void exactFullRefundIsLegalAndCapsAtOriginalShare() {
        Map<String, Long> result = RefundService.allocate(
                Map.of("L1", 188L, "L2", 312L), Map.of(), 500);
        assertEquals(188L, result.get("L1"));
        assertEquals(312L, result.get("L2"));
        assertEquals(500L, sum(result), "恰退满合法");
    }

    @Test
    void shareDescBeforeLineAscWhenUnequal() {
        // AA 与 ZZ 份额相同且高于 MM：剩余分先给编号更小的 AA，而非份额相同的 ZZ
        Map<String, Long> result = RefundService.allocate(
                Map.of("ZZ", 100L, "AA", 100L, "MM", 50L), Map.of(), 10);
        // floor: AA 4、ZZ 4、MM 2 = 10，无剩余；再用一个有剩余的例子
        Map<String, Long> withRemainder = RefundService.allocate(
                Map.of("ZZ", 100L, "AA", 100L, "MM", 50L), Map.of(), 11);
        // floor: 11*100/250=4,4; 11*50/250=2 -> 10，余 1 给 AA（份额并列、编号升序）
        assertEquals(5L, withRemainder.get("AA"));
        assertEquals(4L, withRemainder.get("ZZ"));
        assertEquals(2L, withRemainder.get("MM"));
        assertEquals(11L, sum(withRemainder));
        assertEquals(10L, sum(result));
    }

    @Test
    void structuredFingerprintDistinguishesDelimiterInsideLegalText() {
        // 朴素 '|' 拼接会碰撞的两组参数，长度前缀编码必须区分
        String a = RefundService.structuredFingerprint("S", "a|", "b");
        String b = RefundService.structuredFingerprint("S", "a", "|b");
        assertNotEquals(a, b);

        // 含长度前缀样式文本 "1:" 也不能与跨字段切分碰撞
        String c = RefundService.structuredFingerprint("S", "a1:b", "c");
        String d = RefundService.structuredFingerprint("S", "a", "1:bc");
        assertNotEquals(c, d);

        // 版本差异（"1" 与 "01"）必须区分
        assertNotEquals(
                RefundService.structuredFingerprint("S", "R1", "1"),
                RefundService.structuredFingerprint("S", "R1", "01"));

        // 同参数确定性
        assertEquals(
                RefundService.structuredFingerprint("S", "a|", "b"),
                RefundService.structuredFingerprint("S", "a|", "b"));
    }
}
