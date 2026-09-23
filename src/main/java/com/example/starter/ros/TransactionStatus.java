package com.example.starter.ros;

/**
 * 整笔事务的处理结果。
 */
public enum TransactionStatus {
    /** 全部目标节点接受，版本与事件已原子提交 */
    COMMITTED,
    /** 存在拒绝/超时/版本冲突，数据库值、节点应用值与事件流全部保持原状 */
    ROLLED_BACK
}
