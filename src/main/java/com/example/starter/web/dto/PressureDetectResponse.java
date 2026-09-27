package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 压力检测与联动结果。
 *
 * @param grade                   LOW / NORMAL / HIGH（恰等限值为 NORMAL）
 * @param accepted                是否唯一受理联动（并发时仅一个 true）
 * @param taskId                  联动任务ID（合格无联动时为 null）
 * @param pressureBeforeMpa       调节前压力 MPa
 * @param pressureAfterMpa        调节后压力 MPa（调压到目标值）
 * @param injectionRateBeforeMgM3 调节前加注速率折算浓度 mg/m3
 * @param injectionRateAfterMgM3  调节后加注速率折算浓度 mg/m3（被储罐安全储备拦截时不变）
 * @param injectionDoseMg         本次按比例加注量 mg；被拦截或锁阀时为 0，不伪报加注成功
 * @param late                    TRUE 表示日结封存后迟到，仅记事件不联动
 * @param injectionBlockedByReserve TRUE 表示已绑定储罐且预占将使可用量低于安全储备，
 *                                加注未下发（已生成待补液事件），调压仍执行
 * @param idempotentReplay        是否为同键同参重放
 */
public record PressureDetectResponse(
        String grade,
        boolean accepted,
        Long taskId,
        BigDecimal pressureBeforeMpa,
        BigDecimal pressureAfterMpa,
        BigDecimal injectionRateBeforeMgM3,
        BigDecimal injectionRateAfterMgM3,
        BigDecimal injectionDoseMg,
        boolean late,
        boolean injectionBlockedByReserve,
        boolean idempotentReplay) implements Replayable<PressureDetectResponse> {

    @Override
    public PressureDetectResponse withReplay(boolean replay) {
        return new PressureDetectResponse(grade, accepted, taskId, pressureBeforeMpa, pressureAfterMpa,
                injectionRateBeforeMgM3, injectionRateAfterMgM3, injectionDoseMg, late,
                injectionBlockedByReserve, replay);
    }
}
