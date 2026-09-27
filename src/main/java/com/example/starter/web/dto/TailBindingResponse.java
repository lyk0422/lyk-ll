package com.example.starter.web.dto;

/**
 * 列尾装置绑定视图。
 *
 * @param bindingId      绑定主键
 * @param jobId          所属作业ID
 * @param deviceNo       装置编号
 * @param tailCarNo      绑定冻结的编组尾车车辆号
 * @param consistVersion 绑定冻结的编组版本
 * @param status         绑定状态：ACTIVE 生效中 / RELEASED 已解除
 * @param releaseReason  解除原因：CLOSURE 销记 / EARLY_UNBIND 提前解绑；null 生效中
 */
public record TailBindingResponse(
        long bindingId,
        long jobId,
        String deviceNo,
        String tailCarNo,
        int consistVersion,
        String status,
        String releaseReason) {
}
