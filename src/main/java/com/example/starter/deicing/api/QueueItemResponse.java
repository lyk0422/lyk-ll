package com.example.starter.deicing.api;

import com.example.starter.deicing.model.DeiceType;
import com.example.starter.deicing.model.QueueStatus;

import java.util.List;

/**
 * 单条排队记录视图。所有时刻字段均为 UTC 时区 epoch 毫秒。
 *
 * @param requestId            申请幂等键
 * @param seq                  申请序号（同刻到达定序依据）
 * @param flightNo             航班号
 * @param stand                机位号
 * @param deiceType            除冰类型
 * @param requiredBays         所需车位数
 * @param targetDepartureTs    目标起飞时刻
 * @param expectedDepartureTs  最新预计起飞时刻
 * @param deadlineTs           截止时刻（起飞时刻减除冰时长）
 * @param status               当前状态
 * @param roundNo              除冰轮次：首次为 1，重新除冰递增
 * @param actualStartTs        作业实际开始时刻，未开始为空
 * @param actualFinishTs       作业实际完成时刻，未完成为空
 * @param hotExpiresTs         两步法防冰液失效终点（恰在该点起飞仍有效），不适用为空
 * @param earliestReschedTs    HOT 风险后最早可重排时刻，无风险为空
 * @param cancelReason         取消原因
 * @param assignments          该车位/除冰车占用安排时间线
 */
public record QueueItemResponse(
        String requestId,
        long seq,
        String flightNo,
        String stand,
        DeiceType deiceType,
        int requiredBays,
        long targetDepartureTs,
        long expectedDepartureTs,
        long deadlineTs,
        QueueStatus status,
        int roundNo,
        Long actualStartTs,
        Long actualFinishTs,
        Long hotExpiresTs,
        Long earliestReschedTs,
        String cancelReason,
        List<AssignmentView> assignments
) {
}
