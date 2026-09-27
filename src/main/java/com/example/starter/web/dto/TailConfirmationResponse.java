package com.example.starter.web.dto;

/**
 * 列尾一致性确认视图。
 *
 * @param confirmationId 确认主键
 * @param bindingId      所属绑定主键
 * @param confirmer      确认人
 * @param tailCarNo      确认的尾车车辆号
 * @param consistVersion 确认的编组版本
 * @param valid          是否有效：摘挂变更或解绑后失效
 */
public record TailConfirmationResponse(
        long confirmationId,
        long bindingId,
        String confirmer,
        String tailCarNo,
        int consistVersion,
        boolean valid) {
}
