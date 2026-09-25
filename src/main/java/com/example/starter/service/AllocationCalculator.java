package com.example.starter.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 限电分摊计算器（纯函数，可独立单测）。
 *
 * <p>规则：全场可用出力恰等于（或低于）目标时不降机；限电差额按各机组可降空间比例分摊，
 * 单机最低保留额定容量 15% 的技术出力（恰降至 15% 合法）；分摊取整到 kW 且合计恰等于
 * 指令目标，尾差归入可降空间最大的机组（并列时取 id 最小者）。
 */
public final class AllocationCalculator {

    private AllocationCalculator() {
    }

    /**
     * 参与分摊的机组快照。
     */
    public record Unit(long turbineId, int availableKw, int minKw) {
    }

    /**
     * 单台机组的分摊结果。
     */
    public record Allocation(long turbineId, int availableKw, int minKw, int headroomKw,
                             int targetKw) {
    }

    /**
     * 分摊结果。curtailed=false 表示未降机。
     */
    public record Result(List<Allocation> allocations, int totalAvailableKw, int reductionKw,
                         boolean curtailed) {
    }

    /**
     * 计算分摊。目标低于全场最低技术出力之和时抛出 IllegalArgumentException。
     */
    public static Result allocate(List<Unit> units, int targetKw) {
        if (units.isEmpty()) {
            throw new IllegalArgumentException("风场下没有可分摊的机组");
        }
        if (targetKw < 0) {
            throw new IllegalArgumentException("目标出力不能为负");
        }
        long totalAvailable = units.stream().mapToLong(Unit::availableKw).sum();
        long totalMin = units.stream().mapToLong(Unit::minKw).sum();
        if (targetKw < totalMin) {
            throw new IllegalArgumentException(
                    "目标出力 " + targetKw + " kW 低于全场最低技术出力之和 " + totalMin + " kW");
        }
        if (targetKw >= totalAvailable) {
            // 恰等目标或目标高于可用出力：不降机
            List<Allocation> allocations = units.stream()
                    .map(u -> new Allocation(u.turbineId(), u.availableKw(), u.minKw(),
                            u.availableKw() - u.minKw(), u.availableKw()))
                    .toList();
            return new Result(allocations, (int) totalAvailable, 0, false);
        }

        long reduction = totalAvailable - targetKw;
        long totalHeadroom = totalAvailable - totalMin;
        List<Allocation> allocations = new ArrayList<>(units.size());
        long sum = 0;
        for (Unit u : units) {
            long headroom = u.availableKw() - u.minKw();
            long cut = reduction * headroom / totalHeadroom;
            int target = (int) (u.availableKw() - cut);
            allocations.add(new Allocation(u.turbineId(), u.availableKw(), u.minKw(),
                    (int) headroom, target));
            sum += target;
        }
        // 尾差归入可降空间最大机组（并列取输入序最前者，即 id 最小）；
        // 若触及 15% 下限，剩余尾差顺延到次大机组，保证合计恰等于目标。
        long tail = sum - targetKw;
        if (tail > 0) {
            List<Allocation> mutable = new ArrayList<>(allocations);
            List<Integer> order = new ArrayList<>(mutable.size());
            for (int i = 0; i < mutable.size(); i++) {
                order.add(i);
            }
            order.sort((a, b) -> Integer.compare(mutable.get(b).headroomKw(),
                    mutable.get(a).headroomKw()));
            for (int idx : order) {
                if (tail == 0) {
                    break;
                }
                Allocation cur = mutable.get(idx);
                long room = cur.targetKw() - cur.minKw();
                long take = Math.min(room, tail);
                if (take > 0) {
                    mutable.set(idx, new Allocation(cur.turbineId(), cur.availableKw(),
                            cur.minKw(), cur.headroomKw(), (int) (cur.targetKw() - take)));
                    tail -= take;
                }
            }
            if (tail > 0) {
                throw new IllegalArgumentException("尾差无法在不越 15% 下限的前提下分摊");
            }
            allocations = mutable;
        }
        return new Result(List.copyOf(allocations), (int) totalAvailable, (int) reduction, true);
    }
}
