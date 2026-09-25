package com.example.starter.metro.fare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 票价规则纯单元测试：起步价、5 公里恰满加价档、换乘链只计一次起步价。
 */
class FareServiceTest {

    private final FareService fareService = new FareService();

    @Test
    void baseFareCoversUpToFourKm() {
        assertEquals(200L, fareService.fareFenForKm(0));
        assertEquals(200L, fareService.fareFenForKm(3));
        assertEquals(200L, fareService.fareFenForKm(4));
    }

    @Test
    void exactFiveKmBlockCountsAsFullBlock() {
        // 4+1=5 公里：超出 1 公里，不足 5 公里按 5 公里计 -> 3 元
        assertEquals(300L, fareService.fareFenForKm(5));
        // 4+5=9 公里：恰满 5 公里按加价档计 -> 3 元
        assertEquals(300L, fareService.fareFenForKm(9));
        // 10 公里：超出 6 公里，进入第二档 -> 4 元
        assertEquals(400L, fareService.fareFenForKm(10));
        // 14 公里：恰满两个加价档 -> 4 元
        assertEquals(400L, fareService.fareFenForKm(14));
        // 15 公里 -> 5 元
        assertEquals(500L, fareService.fareFenForKm(15));
    }

    @Test
    void chainedFareChargesBaseFareOnlyOnce() {
        // 第一段 7 公里：3 元
        long first = fareService.chainedFareFen(0, 7);
        assertEquals(300L, first);
        // 换乘续程 5 公里：链累计 12 公里票价 400 - 已计 300 = 100，不再收起步价
        long second = fareService.chainedFareFen(7, 5);
        assertEquals(100L, second);
        // 链上合计等于 12 公里单程票价
        assertEquals(fareService.fareFenForKm(12), first + second);
    }
}
