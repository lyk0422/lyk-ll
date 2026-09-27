package com.example.starter.web.dto;

/**
 * 列尾与编组尾车一致性双人确认结果。
 *
 * @param confirmationId  确认主键
 * @param bindingId       所属绑定ID
 * @param deviceNo        装置编号
 * @param jobId           受控作业ID
 * @param tailCarNo       确认的尾车车辆号
 * @param consistVersion  确认的编组版本
 * @param confirmer1      确认人1
 * @param confirmer2      确认人2
 * @param valid           是否仍有效（摘挂变更/提前解绑/销记后失效）
 * @param deviceVersion   确认成功后的装置版本
 */
public record EotConfirmationResponse(
        long confirmationId,
        long bindingId,
        String deviceNo,
        long jobId,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        boolean valid,
        int deviceVersion) {
}
