package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.deicing.db.DeicingAllocationPo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 半开区间 [start, end) 车位窗口计算单元测试：端点相接不冲突，内部重叠冲突。
 */
class SchedulingCalculatorTest {

    private final SchedulingCalculator calculator = new SchedulingCalculator();
    private final Instant t0 = Instant.parse("2026-09-25T10:00:00Z");

    private DeicingAllocationPo occupancy(String bay, Instant start, Instant end) {
        DeicingAllocationPo po = new DeicingAllocationPo();
        po.setBayCode(bay);
        po.setStartTime(start);
        po.setEndTime(end);
        return po;
    }

    @Test
    void endpointTouchingDoesNotConflict() {
        // 占用 [10:00,10:30)，作业 30 分钟从 10:30 开始：端点相接，可放入
        List<SchedulingCalculator.BayWindow> windows = calculator.freeWindows(
                "BAY-01", t0, Duration.ofMinutes(30),
                List.of(occupancy("BAY-01", t0, t0.plusSeconds(1800))));

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).windowStart()).isEqualTo(t0.plusSeconds(1800));
    }

    @Test
    void internalOverlapConflicts() {
        // 占用 [10:00,10:31)，30 分钟作业从 10:30 开始会内部重叠，必须推迟到 10:31
        List<SchedulingCalculator.BayWindow> windows = calculator.freeWindows(
                "BAY-01", t0, Duration.ofMinutes(30),
                List.of(occupancy("BAY-01", t0, t0.plusSeconds(1860))));

        assertThat(windows.get(0).windowStart()).isEqualTo(t0.plusSeconds(1860));
    }

    @Test
    void freeWindowBeforeOccupancy() {
        // 占用 [10:40,11:00)，10:00 起有 40 分钟空闲，可容纳 30 分钟作业
        List<SchedulingCalculator.BayWindow> windows = calculator.freeWindows(
                "BAY-01", t0, Duration.ofMinutes(30),
                List.of(occupancy("BAY-01", t0.plusSeconds(2400), t0.plusSeconds(3600))));

        assertThat(windows).extracting(SchedulingCalculator.BayWindow::windowStart)
                .containsExactly(t0, t0.plusSeconds(3600));
    }

    @Test
    void touchingOccupanciesMergeIntoOneBusySpan() {
        // [10:00,10:30) 与 [10:30,11:00) 端点相接，合并为连续占用，11:00 才空闲
        List<SchedulingCalculator.BayWindow> windows = calculator.freeWindows(
                "BAY-01", t0, Duration.ofMinutes(30),
                List.of(occupancy("BAY-01", t0, t0.plusSeconds(1800)),
                        occupancy("BAY-01", t0.plusSeconds(1800), t0.plusSeconds(3600))));

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).windowStart()).isEqualTo(t0.plusSeconds(3600));
    }
}
