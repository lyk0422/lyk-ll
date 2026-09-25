package com.example.starter.irrigation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShortageAllocatorTest {

    @Test
    void allocatesProportionallyWithLargestRemainderRounding() {
        // 欠供 50/30/20（合计 100），加购 10：天然整除 5/3/2
        List<ShortageAllocator.Allocation> result = ShortageAllocator.allocateByShortage(
                List.of(
                        new ShortageAllocator.OutletShortage("D001", 50),
                        new ShortageAllocator.OutletShortage("D002", 30),
                        new ShortageAllocator.OutletShortage("D003", 20)),
                10);
        assertThat(result).extracting(a -> a.outletCode() + "=" + a.allocatedCubicMeter())
                .containsExactly("D001=5", "D002=3", "D003=2");
    }

    @Test
    void allocatesRemainderByLargestRemainderMethod() {
        // 欠供 1/1/1，加购 2：floor 各 0，差额 2 按余额（并列）补给编号较小者 D001、D002
        List<ShortageAllocator.Allocation> result = ShortageAllocator.allocateByShortage(
                List.of(
                        new ShortageAllocator.OutletShortage("D003", 1),
                        new ShortageAllocator.OutletShortage("D001", 1),
                        new ShortageAllocator.OutletShortage("D002", 1)),
                2);
        assertThat(result).extracting(a -> a.outletCode() + "=" + a.allocatedCubicMeter())
                .containsExactly("D001=1", "D002=1", "D003=0");
        assertThat(result.stream().mapToLong(ShortageAllocator.Allocation::allocatedCubicMeter).sum())
                .isEqualTo(2L);
    }

    @Test
    void allocatesExactProportionalWithUnevenShortages() {
        // 欠供 70/20/10，加购 3：floor(2.1)=2, floor(0.6)=0, floor(0.3)=0；
        // 差额 1 个，余额：D001=3*70-2*100=10；D002=60；D003=30 -> 补给 D002
        List<ShortageAllocator.Allocation> result = ShortageAllocator.allocateByShortage(
                List.of(
                        new ShortageAllocator.OutletShortage("D001", 70),
                        new ShortageAllocator.OutletShortage("D002", 20),
                        new ShortageAllocator.OutletShortage("D003", 10)),
                3);
        assertThat(result).extracting(a -> a.outletCode() + "=" + a.allocatedCubicMeter())
                .containsExactly("D001=2", "D002=1", "D003=0");
    }

    @Test
    void purchaseAboveTotalShortageFullyCoversAndLeavesRestUnallocated() {
        List<ShortageAllocator.Allocation> result = ShortageAllocator.allocateByShortage(
                List.of(
                        new ShortageAllocator.OutletShortage("D001", 4),
                        new ShortageAllocator.OutletShortage("D002", 6)),
                100);
        assertThat(result).extracting(ShortageAllocator.Allocation::allocatedCubicMeter)
                .containsExactly(4L, 6L);
        assertThat(result.stream().mapToLong(ShortageAllocator.Allocation::allocatedCubicMeter).sum())
                .isEqualTo(10L);
    }

    @Test
    void purchaseEqualToTotalShortageFullyCovers() {
        List<ShortageAllocator.Allocation> result = ShortageAllocator.allocateByShortage(
                List.of(
                        new ShortageAllocator.OutletShortage("D001", 3),
                        new ShortageAllocator.OutletShortage("D002", 7)),
                10);
        assertThat(result).extracting(ShortageAllocator.Allocation::allocatedCubicMeter)
                .containsExactly(3L, 7L);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ShortageAllocator.allocateByShortage(List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShortageAllocator.allocateByShortage(
                List.of(new ShortageAllocator.OutletShortage("D001", 1)), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShortageAllocator.allocateByShortage(
                List.of(new ShortageAllocator.OutletShortage("D001", 0)), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
