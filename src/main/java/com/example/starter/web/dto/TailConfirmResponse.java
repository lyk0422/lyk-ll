package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 列尾装置一致性共同确认响应。
 *
 * @param confirmationId  确认记录ID
 * @param bindingId       所属绑定ID
 * @param jobId           受控作业ID
 * @param deviceNo        装置编号
 * @param tailCarNo       确认的尾车号
 * @param consistVersion  确认的编组版本
 * @param confirmer1      第一名确认人员
 * @param confirmer2      第二名确认人员
 * @param confirmedAt     共同确认时刻（UTC）
 * @param deviceVersion   确认成功后的装置版本
 */
public record TailConfirmResponse(
        long confirmationId,
        long bindingId,
        long jobId,
        String deviceNo,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        Instant confirmedAt,
        int deviceVersion) {
}
