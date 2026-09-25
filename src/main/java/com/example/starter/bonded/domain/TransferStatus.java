package com.example.starter.bonded.domain;

/**
 * 转关单状态机。
 *
 * <pre>
 * APPLIED ──撤销──&gt; CANCELLED（在途核注前可撤销，CANCELLED 为终态）
 * APPLIED ──核注──&gt; ENDORSED（核注与起运仓库存冻结扣减同事务）
 * ENDORSED ──────&gt; IN_TRANSIT
 * IN_TRANSIT ──分批核销──&gt; IN_TRANSIT（部分核销）/ CLOSED（恰好核满，终态）
 * ENDORSED/IN_TRANSIT ──核注后满 48 小时仍有未核销量──&gt; ABNORMAL
 * ABNORMAL ──海关复核确认解除──&gt; IN_TRANSIT
 * </pre>
 */
public enum TransferStatus {

    /** 已申请，尚未起运地核注。 */
    APPLIED,
    /** 已起运地核注，库存已冻结扣减。 */
    ENDORSED,
    /** 在途。 */
    IN_TRANSIT,
    /** 已结案（恰好核满，终态）。 */
    CLOSED,
    /** 已撤销（终态）。 */
    CANCELLED,
    /** 异常（核注后 48 小时未核满，终态式冻结，须海关复核解除）。 */
    ABNORMAL
}
