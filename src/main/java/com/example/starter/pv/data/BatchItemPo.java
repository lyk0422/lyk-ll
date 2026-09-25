package com.example.starter.pv.data;

/**
 * pv_batch_item 表行对象（批次快照明细）。
 */
public record BatchItemPo(
        Long id,
        String batchNo,
        String caseNo,
        int versionNo,
        String snapshotJson,
        int ordinal) {
}
