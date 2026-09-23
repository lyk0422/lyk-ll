package com.example.starter.signing.model;

/**
 * 签署事件链事件类型，顺序即事件发生顺序。
 */
public enum EventType {

    /** 建包。 */
    PACKAGE_CREATED,
    /** 有效签名提交。 */
    SIGNED,
    /** 文档修订（旧修订签名全部失效，流程重置到第一步）。 */
    REVISION_CREATED,
    /** 取消。 */
    CANCELLED,
    /** 到期惰性落事件。 */
    EXPIRED,
    /** 最后一个有效签名完成，证据包冻结。 */
    COMPLETED
}
