package com.example.starter.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 压力链复算点：由原始压力读数与门站限值重新分级，并回查联动任务与两条调节指令。
 *
 * @param requestId           检测请求幂等键
 * @param detectedAt          检测时刻
 * @param pressureMpa         实测压力 MPa
 * @param grade               复算分级 LOW/NORMAL/HIGH（恰等限值合格）
 * @param taskId              联动任务ID，无联动为 null
 * @param taskStatus          联动任务状态
 * @param pressureAdjusted    是否存在调压指令
 * @param injectionAdjusted   是否存在加注指令
 * @param pressureAfterMpa    调节后压力 MPa，无调节为 null
 * @param injectionDoseMg     比例加注量 mg，无加注为 null
 */
public record PressureChainPoint(
        String requestId,
        LocalDateTime detectedAt,
        BigDecimal pressureMpa,
        String grade,
        Long taskId,
        String taskStatus,
        boolean pressureAdjusted,
        boolean injectionAdjusted,
        BigDecimal pressureAfterMpa,
        BigDecimal injectionDoseMg) {
}
