package com.example.starter.irrigation.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 临时加购水量按欠供比例分配的纯计算逻辑。
 *
 * <p>各欠供斗口先取 {@code floor(加购量 × 欠供比例)} 立方米；加购量不超过欠供总额时，
 * 取整差额按最大余额法（Hare-Niemeyer）依次补给 1 立方米，余额并列时补给斗口编号较小者；
 * 加购量大于欠供总额时所有欠供斗口足额补齐，剩余加购量不分配。
 */
public final class ShortageAllocator {

    /** 斗口欠供输入。 */
    public record OutletShortage(String outletCode, long shortageCubicMeter) {}

    /** 斗口获配结果，单位立方米。 */
    public record Allocation(String outletCode, long shortageCubicMeter, long allocatedCubicMeter) {}

    private ShortageAllocator() {
    }

    /**
     * 按欠供比例分配加购水量。
     *
     * @param shortages          欠供斗口列表，欠供量必须为正
     * @param purchaseCubicMeter 加购水量，立方米，必须为正
     * @return 各欠供斗口获配量（按斗口编号升序），合计为加购量与欠供总额的较小值
     */
    public static List<Allocation> allocateByShortage(List<OutletShortage> shortages, long purchaseCubicMeter) {
        if (shortages == null || shortages.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个欠供斗口");
        }
        if (purchaseCubicMeter <= 0) {
            throw new IllegalArgumentException("加购水量必须为正");
        }
        long totalShortage = 0;
        for (OutletShortage shortage : shortages) {
            if (shortage.shortageCubicMeter() <= 0) {
                throw new IllegalArgumentException("欠供量必须为正: " + shortage.outletCode());
            }
            totalShortage += shortage.shortageCubicMeter();
        }

        // 加购量不小于欠供总额：欠供斗口全部足额补齐，余量不分配。
        if (purchaseCubicMeter >= totalShortage) {
            List<Allocation> full = new ArrayList<>();
            for (OutletShortage shortage : shortages) {
                full.add(new Allocation(shortage.outletCode(), shortage.shortageCubicMeter(),
                        shortage.shortageCubicMeter()));
            }
            full.sort(Comparator.comparing(Allocation::outletCode));
            return full;
        }

        // floor(加购量 × 欠供 / 欠供总额)，余额 = 加购量×欠供 - 已配×欠供总额。
        List<Working> workings = new ArrayList<>();
        long allocatedSum = 0;
        for (OutletShortage shortage : shortages) {
            long base = Math.floorDiv(purchaseCubicMeter * shortage.shortageCubicMeter(), totalShortage);
            workings.add(new Working(shortage.outletCode(), shortage.shortageCubicMeter(), base));
            allocatedSum += base;
        }

        // 取整差额按余额大小逐立方补给，余额并列补给编号较小者。
        long remainder = purchaseCubicMeter - allocatedSum;
        long total = totalShortage;
        long purchase = purchaseCubicMeter;
        while (remainder > 0) {
            Working winner = workings.stream()
                    .filter(w -> w.allocated < w.shortage)
                    .max(Comparator.comparingLong((Working w) -> w.remainderWeight(purchase, total))
                            .thenComparing(w -> w.outletCode, Comparator.reverseOrder()))
                    .orElseThrow(() -> new IllegalStateException("差额分配无候选斗口"));
            winner.allocated++;
            remainder--;
        }

        List<Allocation> results = workings.stream()
                .map(w -> new Allocation(w.outletCode, w.shortage, w.allocated))
                .sorted(Comparator.comparing(Allocation::outletCode))
                .toList();
        long sum = results.stream().mapToLong(Allocation::allocatedCubicMeter).sum();
        if (sum != purchaseCubicMeter) {
            throw new IllegalStateException("加购分配合计不等于加购量");
        }
        return results;
    }

    /** 分配过程工作变量。 */
    private static final class Working {
        private final String outletCode;
        private final long shortage;
        private long allocated;

        private Working(String outletCode, long shortage, long allocated) {
            this.outletCode = outletCode;
            this.shortage = shortage;
            this.allocated = allocated;
        }

        /**
         * 余额权重：精确应配额与已配额之差乘总分母，即
         * {@code purchase × shortage - allocated × totalShortage}，越大越优先补差。
         */
        private long remainderWeight(long purchase, long totalShortage) {
            return purchase * shortage - allocated * totalShortage;
        }
    }
}
