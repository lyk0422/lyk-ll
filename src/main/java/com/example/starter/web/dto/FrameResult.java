package com.example.starter.web.dto;

import java.util.List;

/**
 * 帧解析结果。accepted=false 时 reason 给出可区分的拒绝原因，signals 为空。
 *
 * @param accepted     是否成功解码并参与状态更新
 * @param reason       拒绝原因：INVALID_LENGTH / UNKNOWN_ID / MUX_NOT_MATCHED；成功时为 null
 * @param duplicate    是否为相同帧重放（未重复产生状态变更事件）
 * @param stateUpdated 是否因本帧更新了最新状态（重放或旧帧为 false）
 * @param signals      解码出的信号值（不满足多路复用条件的信号不出现）
 */
public record FrameResult(
        boolean accepted,
        String reason,
        boolean duplicate,
        boolean stateUpdated,
        List<SignalValueDto> signals
) {
}
