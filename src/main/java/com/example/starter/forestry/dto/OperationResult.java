package com.example.starter.forestry.dto;

import java.time.LocalDate;

/**
 * 通用操作结果视图（幂等重放时返回首次结果与幂等标记）。
 *
 * @param requestId  幂等键
 * @param idempotent true 表示本次为重放，返回的是首次结果
 * @param refId      首次操作产生/关联的业务主键
 * @param at         操作发生日期（UTC）
 */
public record OperationResult(String requestId, boolean idempotent, String refId, LocalDate at) {
}
