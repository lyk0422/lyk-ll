package com.example.starter.pv.data;

import java.util.Optional;

/**
 * 初报/随访幂等记录数据访问。
 */
public interface SubmissionRepository {

    /**
     * 插入幂等记录（占位或完整均可）；requestId 冲突时抛底层唯一约束异常。
     */
    void insert(SubmissionPo po);

    /**
     * 业务完成后回填个例编号、版本、并入标记与响应 JSON。
     */
    void complete(String requestId, String caseNo, int versionNo, boolean mergedDuplicate,
                  String responseJson);

    /**
     * 按 requestId 查询已有记录。
     */
    Optional<SubmissionPo> findByRequestId(String requestId);
}
