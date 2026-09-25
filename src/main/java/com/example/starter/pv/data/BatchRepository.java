package com.example.starter.pv.data;

import java.util.List;
import java.util.Optional;

/**
 * 递交批次数据访问。
 */
public interface BatchRepository {

    /**
     * 插入批次头。
     */
    void insertBatch(BatchPo po);

    /**
     * 插入批次快照明细。
     */
    void insertItem(BatchItemPo po);

    /**
     * 按批次号查询批次头。
     */
    Optional<BatchPo> findBatchByNo(String batchNo);

    /**
     * 按递交 requestId 查询批次头。
     */
    Optional<BatchPo> findBatchByRequestId(String requestId);

    /**
     * 查询批次全部明细，按批次内顺序升序。
     */
    List<BatchItemPo> findItems(String batchNo);
}
