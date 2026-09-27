package com.example.starter.web.dto;

/**
 * 列尾装置绑定结果；冻结绑定时刻的尾车与编组版本。
 *
 * @param bindingId       绑定主键
 * @param deviceId        装置主键
 * @param deviceNo        装置编号
 * @param jobId           受控作业ID
 * @param tailCarNo       冻结的尾车车辆号
 * @param consistVersion  冻结的编组版本
 * @param status          绑定状态：ACTIVE/RELEASED
 * @param deviceVersion   绑定成功后的装置版本
 */
public record EotBindingResponse(
        long bindingId,
        long deviceId,
        String deviceNo,
        long jobId,
        String tailCarNo,
        int consistVersion,
        String status,
        int deviceVersion) {
}
