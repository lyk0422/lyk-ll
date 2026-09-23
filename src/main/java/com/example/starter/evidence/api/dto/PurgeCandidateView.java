package com.example.starter.evidence.api.dto;

/**
 * 清除批次候选项视图。
 *
 * @param evidenceId  候选证据标识
 * @param finalStatus 最终结果 PURGED/RESCUED，待确认为空
 * @param reason      结果说明，可空
 */
public record PurgeCandidateView(String evidenceId, String finalStatus, String reason) {
}
