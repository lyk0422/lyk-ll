package com.example.starter.deicing.web.dto;

/**
 * 借用/归还写操作的统一响应。
 *
 * @param replay 是否为幂等重放（返回首次结果）
 */
public record BorrowResponse(BorrowView item, boolean replay) {
}
