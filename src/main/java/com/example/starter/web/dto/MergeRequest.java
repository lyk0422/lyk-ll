package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * 工单合并请求：将多张同馈线工单合并为一张新工单。
 *
 * @param sourceOrderIds 被合并的源工单ID列表，至少两张
 */
public record MergeRequest(@NotEmpty List<Long> sourceOrderIds) {
}
