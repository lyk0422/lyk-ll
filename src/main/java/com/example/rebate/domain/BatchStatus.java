package com.example.rebate.domain;

/**
 * 申报批次状态。
 *
 * <p>流转：校验通过 {@code CHECKED}；存在未解除疑点 {@code DOUBTFUL}；正式申报 {@code DECLARED}；
 * 审核通过 {@code APPROVED}；退库核销一致 {@code TREASURED}；到账差异转人工 {@code DIFF_MANUAL}；
 * 撤批整体作废 {@code VOID}；结案 {@code CLOSED}（终态）。</p>
 */
public enum BatchStatus {
    /** 关联校验完成、无疑点，可正式申报。 */
    CHECKED,
    /** 存在未解除疑点，禁止正式申报。 */
    DOUBTFUL,
    /** 已正式申报，批次冻结。 */
    DECLARED,
    /** 审核通过。 */
    APPROVED,
    /** 退库核销完成且金额一致。 */
    TREASURED,
    /** 到账差异超 0.5%，已登记差异并转人工。 */
    DIFF_MANUAL,
    /** 撤批整体作废，关联单证全部释放。 */
    VOID,
    /** 结案终态；重复结案幂等成功，迟到单证仅记事件。 */
    CLOSED
}
