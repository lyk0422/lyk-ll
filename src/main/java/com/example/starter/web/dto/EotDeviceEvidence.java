package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 列尾装置完整证据视图：装置档案与全部绑定历史（销记/解绑后历史保留）。
 *
 * @param deviceId             装置主键
 * @param deviceNo             装置编号
 * @param selfCheckValidUntil  自检合格有效截止时刻（UTC）
 * @param boundJobId           当前绑定作业ID；null 表示空闲
 * @param version              装置当前递增版本
 * @param bindings             全部绑定历史证据（按时间正序）
 */
public record EotDeviceEvidence(
        long deviceId,
        String deviceNo,
        Instant selfCheckValidUntil,
        Long boundJobId,
        int version,
        List<EotBindingEvidence> bindings) {
}
