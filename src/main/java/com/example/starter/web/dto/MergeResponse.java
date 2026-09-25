package com.example.starter.web.dto;

import java.util.List;

/**
 * 工单合并响应。
 *
 * @param newOrderId     合并生成的新工单ID
 * @param sourceOrderIds 被合并的源工单ID列表
 */
public record MergeResponse(long newOrderId, List<Long> sourceOrderIds) {
}
