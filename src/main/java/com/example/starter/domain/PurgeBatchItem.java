package com.example.starter.domain;

/**
 * 清除批次候选行：最终确认时若存在活动保全覆盖该证据则 rescued=true。
 */
public record PurgeBatchItem(
        long id,
        String batchId,
        String evidenceId,
        boolean rescued
) {
}
