package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泵站启停指令受理结果。
 *
 * @param taskId      启停任务ID
 * @param sequenceId  所属启停序列ID
 * @param pumpdownId  所属预降事件ID
 * @param stationCode 泵站代码
 * @param action      动作 START/STOP
 * @param taskTime    启停时刻
 * @param gateOpening 启泵时闸门实际开度（百分比），STOP 时为 null
 * @param reason      启停原因
 * @param replayed    是否为同键重放返回
 */
public record PumpCommandResult(
        Long taskId,
        Long sequenceId,
        Long pumpdownId,
        String stationCode,
        String action,
        LocalDateTime taskTime,
        BigDecimal gateOpening,
        String reason,
        boolean replayed
) {
}
