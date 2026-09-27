package com.example.starter.repo;

import java.time.Instant;

/**
 * 列尾一致性双人确认行记录。
 *
 * @param id              确认主键
 * @param bindingId       所属绑定ID
 * @param deviceId        装置ID
 * @param deviceNo        装置编号
 * @param jobId           受控作业ID
 * @param tailCarNo       确认的尾车车辆号
 * @param consistVersion  确认的编组版本
 * @param confirmer1      确认人1
 * @param confirmer2      确认人2
 * @param valid           是否仍有效
 * @param requestId       幂等请求键
 * @param createdAt       创建时间（UTC）
 */
public record EotConfirmationRow(
        long id,
        long bindingId,
        long deviceId,
        String deviceNo,
        long jobId,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        boolean valid,
        String requestId,
        Instant createdAt) {
}
