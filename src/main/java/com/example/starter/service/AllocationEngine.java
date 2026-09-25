package com.example.starter.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.Turbine;
import com.example.starter.web.ApiBadRequestException;

/**
 * 限电差额分摊算法（纯逻辑，可独立单测）。
 * 规则：全场可用恰等于目标时不降机；限电差额按各机组可降空间（可用出力−技术下限）
 * 比例分摊，单机最低保留技术出力 15%（恰降至 15% 合法）；分摊取整到 kW，
 * 合计恰等于全场目标，尾差归入可降空间最大机组（其装不下时依次溢出到次大机组）。
 */
public final class AllocationEngine {

    private AllocationEngine() {
    }

    /**
     * 按各机组可降空间比例分摊全场目标出力。
     *
     * @param turbines     参与分摊的机组（availablePowerKw 为分摊时最新可用出力）
     * @param farmTargetKw 全场限电后目标出力（kW）
     * @param orderId      指令 id（回填明细）
     * @param cycleNo      轮次号（回填明细）
     * @return 每机组一条分摊明细，目标合计恰等于 farmTargetKw
     */
    public static List<Allocation> allocate(List<Turbine> turbines, int farmTargetKw, long orderId, int cycleNo) {
        if (turbines == null || turbines.isEmpty()) {
            throw new ApiBadRequestException("风场没有可参与分摊的机组");
        }
        int farmAvailable = turbines.stream().mapToInt(Turbine::getAvailablePowerKw).sum();
        // 可实现下限：小风停机风险机组按其实际可用出力计（不能压减到比实际出力还低）。
        int achievableFloor = turbines.stream()
                .mapToInt(t -> Math.min(t.getAvailablePowerKw(), t.getTechnicalMinKw()))
                .sum();

        if (farmTargetKw > farmAvailable) {
            throw new ApiBadRequestException(
                    "目标出力 " + farmTargetKw + " kW 高于全场可用出力 " + farmAvailable + " kW，无需限电");
        }
        if (farmTargetKw < achievableFloor) {
            throw new ApiBadRequestException(
                    "目标出力 " + farmTargetKw + " kW 低于全场可实现下限 " + achievableFloor + " kW");
        }

        int curtailTotal = farmAvailable - farmTargetKw;
        long headroomTotal = turbines.stream()
                .mapToLong(t -> Math.max(0, (long) t.getAvailablePowerKw() - t.getTechnicalMinKw()))
                .sum();

        int n = turbines.size();
        long[] curtail = new long[n];
        // 恰等目标：curtaillTotal=0，所有机组 curtail=0（不降机）。
        if (curtailTotal > 0) {
            long[] rawFloor = new long[n];
            long assigned = 0;
            for (int i = 0; i < n; i++) {
                long headroom = Math.max(0,
                        (long) turbines.get(i).getAvailablePowerKw()
                                - turbines.get(i).getTechnicalMinKw());
                long share = headroomTotal == 0 ? 0 : curtailTotal * headroom / headroomTotal;
                rawFloor[i] = share;
                assigned += share;
            }
            long remainder = curtailTotal - assigned;

            // 尾差归入可降空间最大机组（并列时取机组编码靠前）；装不下则依次填满次大机组。
            List<Integer> orderByHeadroom = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                orderByHeadroom.add(i);
            }
            orderByHeadroom.sort(Comparator
                    .comparingLong((Integer i) -> -Math.max(0,
                            (long) turbines.get(i).getAvailablePowerKw()
                                    - turbines.get(i).getTechnicalMinKw()))
                    .thenComparing(i -> turbines.get(i).getTurbineCode()));

            for (int i = 0; i < n; i++) {
                curtail[i] = rawFloor[i];
            }
            for (int idx : orderByHeadroom) {
                if (remainder == 0) {
                    break;
                }
                long headroom = Math.max(0,
                        (long) turbines.get(idx).getAvailablePowerKw()
                                - turbines.get(idx).getTechnicalMinKw());
                long canTake = Math.min(remainder, headroom - curtail[idx]);
                curtail[idx] += canTake;
                remainder -= canTake;
            }
            if (remainder != 0) {
                // 理论不可达：farmTarget>=可实现下限 已保证可降总额足够。
                throw new ApiBadRequestException("分摊失败：可降空间不足以压减到目标出力");
            }
        }

        List<Allocation> result = new ArrayList<>(n);
        long checkSum = 0;
        for (int i = 0; i < n; i++) {
            Turbine t = turbines.get(i);
            Allocation a = new Allocation();
            a.setOrderId(orderId);
            a.setCycleNo(cycleNo);
            a.setTurbineId(t.getId());
            a.setBaseAvailableKw(t.getAvailablePowerKw());
            a.setCurtailKw((int) curtail[i]);
            a.setTargetPowerKw(t.getAvailablePowerKw() - (int) curtail[i]);
            // 目标不低于“实际可用与技术下限的较小者”：小风机组保持实际出力，其余不低于 15%。
            if (a.getTargetPowerKw() < Math.min(t.getAvailablePowerKw(), t.getTechnicalMinKw())) {
                throw new ApiBadRequestException("分摊结果违反技术出力下限：机组 " + t.getTurbineCode());
            }
            checkSum += a.getTargetPowerKw();
            result.add(a);
        }
        if (checkSum != farmTargetKw) {
            throw new ApiBadRequestException("分摊合计 " + checkSum + " kW 不等于目标 " + farmTargetKw + " kW");
        }
        return result;
    }
}
