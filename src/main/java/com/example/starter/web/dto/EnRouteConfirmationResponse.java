package com.example.starter.web.dto;

/**
 * 途中试验与列尾一致性双人确认响应。
 *
 * @param id                 确认ID
 * @param dispositionId      所属处置单ID
 * @param consistVersion     确认时编组版本
 * @param tailCarNo          确认时当前编组尾车车辆号
 * @param confirmer1         确认人1
 * @param confirmer2         确认人2
 * @param valid              是否有效
 * @param dispositionVersion 处置版本（本次修改后）
 */
public record EnRouteConfirmationResponse(
        long id,
        long dispositionId,
        int consistVersion,
        String tailCarNo,
        String confirmer1,
        String confirmer2,
        boolean valid,
        int dispositionVersion) {
}
