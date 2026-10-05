package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 升温票执行阶段记录，与票的阶段推进在同一事务写入。
 *
 * @param id                 主键
 * @param ticketId           所属升温票
 * @param stageNo            执行阶段序号（票内单调递增）
 * @param stageType          RAMP 升温段 / HOLD 保温段
 * @param curveStageNo       对应合成曲线阶段序号，保温段记录其承接的升温段序号
 * @param curveVersion       曲线版本号（随阶段落库，保证推进与版本记录原子可溯）
 * @param targetTemp         阶段目标温度（℃），恰等于判达标
 * @param maxDurationMinutes 升温段最长用时（分钟），恰满未达目标自动转保温段；保温段为 null
 * @param startedAt          阶段开始时刻
 * @param confirmedAt        双人复核完成时刻，未完成为 null
 * @param confirmOperatorA   第一名确认人
 * @param confirmOperatorB   第二名确认人
 */
public record WarmUpStage(
        Long id,
        long ticketId,
        int stageNo,
        WarmUpStageType stageType,
        int curveStageNo,
        String curveVersion,
        BigDecimal targetTemp,
        Long maxDurationMinutes,
        LocalDateTime startedAt,
        LocalDateTime confirmedAt,
        String confirmOperatorA,
        String confirmOperatorB) {
}
