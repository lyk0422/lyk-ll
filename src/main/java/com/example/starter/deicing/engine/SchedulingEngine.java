package com.example.starter.deicing.engine;

import com.example.starter.deicing.po.AssignmentPO;

import java.util.List;

/**
 * 资源排定引擎：在既有半开区间占用之上，为一次除冰寻找最早可行窗口。
 */
public interface SchedulingEngine {

    /**
     * 一次排定结果。
     *
     * @param startTs     窗口起点（含）
     * @param endTs       窗口终点（不含）
     * @param assignments 车位移交除冰车的配对占用（数量等于所需车位数）
     */
    record SchedulePlan(long startTs, long endTs, List<AssignmentPO> assignments) {
    }

    /**
     * 计算最早可行窗口。
     *
     * @param requestId     申请号（写入占用）
     * @param roundNo       除冰轮次
     * @param requiredBays  所需车位数
     * @param durationMs    作业时长（毫秒）
     * @param earliestStart 最早允许开始时刻（通常为当前时刻）
     * @param bayIds        全部可选车位
     * @param truckIds      全部可选除冰车
     * @param existing      既有有效占用（RESERVED/ACTIVE/DONE）
     * @return 最早可行排定
     */
    SchedulePlan plan(String requestId, int roundNo, int requiredBays, long durationMs,
                      long earliestStart, List<String> bayIds, List<String> truckIds,
                      List<AssignmentPO> existing);
}
