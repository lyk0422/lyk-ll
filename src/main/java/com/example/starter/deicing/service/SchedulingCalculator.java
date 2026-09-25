package com.example.starter.deicing.service;

import com.example.starter.deicing.db.DeicingAllocationPo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 车位半开区间 [start, end) 调度计算：端点相接不冲突，内部重叠冲突。
 */
@Component
public class SchedulingCalculator {

    /**
     * 单个车位上的一段可放置窗口。
     */
    public record BayWindow(String bayCode, Instant windowStart, Instant windowEnd) {

        /** 窗口内能否容纳 duration 长度的作业（收尾端点相接视为可容纳）。 */
        public boolean fits(Duration duration) {
            return !windowStart.plus(duration).isAfter(windowEnd);
        }
    }

    /**
     * 计算某车位在 earliest 之后可容纳 duration 作业的全部空闲窗口（半开区间）。
     * 占用按结束时刻排序合并；相接区间 [a,b) 与 [b,c) 视为连续占用。
     */
    public List<BayWindow> freeWindows(String bayCode, Instant earliest, Duration duration,
                                       List<DeicingAllocationPo> occupied) {
        List<DeicingAllocationPo> sorted = occupied.stream()
                .filter(a -> a.getBayCode().equals(bayCode))
                .filter(a -> a.getEndTime().isAfter(earliest))
                .sorted(Comparator.comparing(DeicingAllocationPo::getStartTime)
                        .thenComparing(DeicingAllocationPo::getEndTime))
                .toList();

        List<BayWindow> windows = new ArrayList<>();
        Instant cursor = earliest;
        for (DeicingAllocationPo o : sorted) {
            if (!o.getStartTime().isAfter(cursor)) {
                // 区间覆盖或端点相接：推进游标到较晚结束点
                if (o.getEndTime().isAfter(cursor)) {
                    cursor = o.getEndTime();
                }
                continue;
            }
            windows.add(new BayWindow(bayCode, cursor, o.getStartTime()));
            cursor = o.getEndTime();
        }
        windows.add(new BayWindow(bayCode, cursor, Instant.MAX));
        return windows.stream().filter(w -> w.fits(duration)).toList();
    }
}
