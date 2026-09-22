package com.example.starter.arbitration.dto;

/**
 * 指令受理结果。
 *
 * @param commandId 指令ID
 * @param status    指令状态：QUEUED 已入待执行队列
 */
public record CommandResponse(long commandId, String status) {
}
