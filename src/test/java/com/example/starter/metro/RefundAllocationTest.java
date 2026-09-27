package com.example.starter.metro;

import java.util.List;
import java.util.Map;

import com.example.starter.metro.RefundRepository.LineShareRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款分摊算法纯单元测试：按原线路实收份额向下取整，尾差按原份额降序、
 * 线路编号升序循环分给尚有可退余额的线路；合计等于退款且不超各线路余额。
 */
class RefundAllocationTest {

    private LineShareRow share(String line, long original, long refunded) {
        return new LineShareRow(0L, "T1", line, original, refunded);
    }

    @Test
    void floorThenRemainderGoesToLargestShareFirst() {
        // 原实收 500：L1=188、L2=312；退 100：37.6->37、62.4->62，余 1 分给份额更大的 L2
        Map<String, Long> result = RefundService.allocateRefund(
                List.of(share("L1", 188, 0), share("L2", 312, 0)), 100);
        assertEquals(37L, result.get("L1"));
        assertEquals(63L, result.get("L2"));
        assertEquals(100L, result.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void remainderTieBreaksByLineCodeAscending() {
        // 两线路份额相同：尾差按线路编号升序给 L1
        Map<String, Long> result = RefundService.allocateRefund(
                List.of(share("L1", 100, 0), share("L2", 100, 0)), 101);
        assertEquals(51L, result.get("L1"));
        assertEquals(50L, result.get("L2"));
        assertEquals(101L, result.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void allocationIsLimitedByPerLineAvailableAndCycles() {
        // L1 已退 180（仅剩 8），L2 全量 312 可退；退 100：
        // floor L1=min(8,37)=8，L2=62；余 30 全部分给尚有余额的 L2 -> L2=92
        Map<String, Long> result = RefundService.allocateRefund(
                List.of(share("L1", 188, 180), share("L2", 312, 0)), 100);
        assertEquals(8L, result.get("L1"));
        assertEquals(92L, result.get("L2"));
        assertEquals(100L, result.values().stream().mapToLong(Long::longValue).sum());
        assertTrue(result.get("L1") + 180 <= 188);
    }

    @Test
    void exactFullAvailableIsLegal() {
        Map<String, Long> result = RefundService.allocateRefund(
                List.of(share("L1", 188, 0), share("L2", 312, 0)), 500);
        assertEquals(188L, result.get("L1"));
        assertEquals(312L, result.get("L2"));
    }

    @Test
    void refundBeyondAvailableIsRejected() {
        MetroException ex = assertThrows(MetroException.class, () -> RefundService.allocateRefund(
                List.of(share("L1", 188, 188), share("L2", 312, 300)), 100));
        assertEquals(422, ex.getStatus().value());
        assertEquals("REFUND_EXCEEDS_AVAILABLE", ex.getCode());
    }
}
