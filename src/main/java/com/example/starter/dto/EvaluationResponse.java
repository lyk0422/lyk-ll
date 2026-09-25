package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 失衡判定 / 效果评估响应。
 *
 * @param requestId     请求幂等键
 * @param taskId        所属调节任务ID
 * @param phase         评估阶段 JUDGMENT / RE_EVAL
 * @param balanced      回水温度极差是否不超过 2℃（恰 2℃合格）
 * @param maxReturnTemp 最高回水温度，单位:℃
 * @param minReturnTemp 最低回水温度，单位:℃
 * @param tempDiff      回水温度极差，单位:℃
 * @param underFlow     欠流单元编码列表
 * @param overFlow      过流单元编码列表
 * @param units         各单元明细，支持复算
 * @param evaluatedAt   评估时刻(UTC)
 * @param replay        是否为同键同参重放返回
 */
public record EvaluationResponse(
        String requestId,
        Long taskId,
        String phase,
        boolean balanced,
        BigDecimal maxReturnTemp,
        BigDecimal minReturnTemp,
        BigDecimal tempDiff,
        List<String> underFlow,
        List<String> overFlow,
        List<UnitEvaluation> units,
        Instant evaluatedAt,
        boolean replay
) {
}
