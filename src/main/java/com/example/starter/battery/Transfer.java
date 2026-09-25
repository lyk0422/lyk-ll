package com.example.starter.battery;

/**
 * 站间调拨单。
 *
 * @param id 调拨单号
 * @param batteryCode 调拨电池编码
 * @param fromStation 调出站点
 * @param toStation 调入站点
 * @param status 调拨状态
 * @param initiatedAt 发起时间（UTC epoch 毫秒）
 * @param signedAt 签收时间（UTC epoch 毫秒），未签收为空
 * @param exceptionAt 转异常时间（UTC epoch 毫秒），未异常为空
 * @param writtenOffAt 人工核销时间（UTC epoch 毫秒），未核销为空
 */
public record Transfer(
        long id,
        String batteryCode,
        String fromStation,
        String toStation,
        TransferStatus status,
        long initiatedAt,
        Long signedAt,
        Long exceptionAt,
        Long writtenOffAt) {
}
