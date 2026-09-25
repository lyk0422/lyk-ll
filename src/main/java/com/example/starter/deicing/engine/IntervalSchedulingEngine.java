package com.example.starter.deicing.engine;

import com.example.starter.deicing.model.AssignmentState;
import com.example.starter.deicing.po.AssignmentPO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 半开区间排定引擎。
 *
 * <p>每次除冰同时占用 requiredBays 个车位与同数量除冰车，一车位配一车辆；
 * 既有占用 {@code [s,e)} 与候选窗口 {@code [t,t+d)} 内部重叠当且仅当
 * {@code s < t+d 且 e > t}，故端点相接（{@code e==t} 或 {@code s==t+d}）不冲突。
 * 可行最早起点只可能是最早允许时刻或某条既有占用的终点，按候选时刻升序扫描即可。</p>
 */
@Component
public class IntervalSchedulingEngine implements SchedulingEngine {

    @Override
    public SchedulePlan plan(String requestId, int roundNo, int requiredBays, long durationMs,
                             long earliestStart, List<String> bayIds, List<String> truckIds,
                             List<AssignmentPO> existing) {
        if (requiredBays <= 0 || durationMs <= 0) {
            throw new IllegalArgumentException("所需车位数与作业时长必须为正");
        }
        if (bayIds.size() < requiredBays || truckIds.size() < requiredBays) {
            throw new IllegalArgumentException("车位或除冰车数量不足以满足所需车位数");
        }

        TreeSet<Long> candidates = new TreeSet<>();
        candidates.add(earliestStart);
        for (AssignmentPO a : existing) {
            if (a.getEndTs() >= earliestStart) {
                candidates.add(a.getEndTs());
            }
        }

        for (long start : candidates) {
            if (start < earliestStart) {
                continue;
            }
            long end = start + durationMs;
            List<String> freeBays = new ArrayList<>();
            for (String bayId : bayIds) {
                if (isResourceFree(bayId, true, start, end, existing)) {
                    freeBays.add(bayId);
                }
            }
            List<String> freeTrucks = new ArrayList<>();
            for (String truckId : truckIds) {
                if (isResourceFree(truckId, false, start, end, existing)) {
                    freeTrucks.add(truckId);
                }
            }
            if (freeBays.size() >= requiredBays && freeTrucks.size() >= requiredBays) {
                List<AssignmentPO> assignments = new ArrayList<>(requiredBays);
                for (int i = 0; i < requiredBays; i++) {
                    AssignmentPO po = new AssignmentPO();
                    po.setRequestId(requestId);
                    po.setRoundNo(roundNo);
                    po.setBayId(freeBays.get(i));
                    po.setTruckId(freeTrucks.get(i));
                    po.setStartTs(start);
                    po.setEndTs(end);
                    po.setState(AssignmentState.RESERVED);
                    assignments.add(po);
                }
                return new SchedulePlan(start, end, assignments);
            }
        }
        throw new IllegalStateException("存在候选时刻却未找到可行窗口，不应发生");
    }

    /**
     * 判断单个资源（车位或除冰车）在 [start,end) 内是否空闲。
     */
    private boolean isResourceFree(String resourceId, boolean bay, long start, long end,
                                   List<AssignmentPO> existing) {
        for (AssignmentPO a : existing) {
            String occupiedId = bay ? a.getBayId() : a.getTruckId();
            if (!resourceId.equals(occupiedId)) {
                continue;
            }
            // 半开区间内部重叠判定：端点相接不冲突
            if (a.getStartTs() < end && a.getEndTs() > start) {
                return false;
            }
        }
        return true;
    }
}
