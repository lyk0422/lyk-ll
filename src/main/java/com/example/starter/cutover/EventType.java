package com.example.starter.cutover;

/**
 * 步骤回报/申请级事件类型。
 */
public enum EventType {
    /** 正常步骤回报 */
    STEP_REPORT,
    /** 回退指令命中记录 */
    ROLLBACK_TRIGGER,
    /** 封板后补报的迟到步骤 */
    LATE_REPORT,
    /** 封板事件 */
    SEAL,
    /** 审批指令 */
    APPROVE,
    /** 开始窗口执行 */
    EXECUTE_START,
    /** 窗口超期收口判定 */
    EXPIRE_CHECK,
    /** 回退完成 */
    ROLLBACK_COMPLETE,
    /** 批次发布时成员绑定批次 */
    BATCH_BIND,
    /** 前置回退/超期传播导致的依赖阻断 */
    DEPENDENCY_BLOCK
}
