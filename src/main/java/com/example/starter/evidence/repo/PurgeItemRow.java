package com.example.starter.evidence.repo;

import com.example.starter.evidence.domain.PurgeItemStatus;

/**
 * 清除批次候选项表行。
 *
 * @param batchId     所属批次标识
 * @param evidenceId  候选证据标识
 * @param finalStatus 最终结果，空表示待确认
 * @param reason      结果说明，可空
 */
public record PurgeItemRow(
        String batchId,
        String evidenceId,
        PurgeItemStatus finalStatus,
        String reason) {
}
