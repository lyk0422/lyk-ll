package com.example.starter.deicing.api;

import com.example.starter.deicing.model.AssignmentState;

/**
 * 车位/除冰车占用半开区间视图 [startTs, endTs)。
 *
 * @param requestId  对应申请号
 * @param roundNo    除冰轮次：首次为 1，重新除冰递增
 * @param bayId      车位编号
 * @param truckId    除冰车编号
 * @param startTs    占用起点（含）
 * @param endTs      占用终点（不含），端点相接不冲突
 * @param state      占用状态
 */
public record AssignmentView(
        String requestId,
        int roundNo,
        String bayId,
        String truckId,
        long startTs,
        long endTs,
        AssignmentState state
) {
}
