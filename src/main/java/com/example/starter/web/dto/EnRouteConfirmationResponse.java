package com.example.starter.web.dto;

/**
 * 途中双人确认响应。
 *
 * @param id              确认主键
 * @param incidentId      所属处置单ID
 * @param jobId           所属作业ID
 * @param tailCarNo       确认时的尾车车辆号
 * @param consistVersion  确认时的编组版本
 * @param confirmer1      确认人1
 * @param confirmer2      确认人2
 * @param valid           是否仍有效
 * @param incidentVersion 处置单新版本
 */
public record EnRouteConfirmationResponse(
        long id,
        long incidentId,
        long jobId,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        boolean valid,
        int incidentVersion) {
}
