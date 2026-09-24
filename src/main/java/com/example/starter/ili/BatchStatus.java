package com.example.starter.ili;

/**
 * 对齐批次状态。SEALED 为终态：封板后任何修改与迟到复核均被拒绝。
 */
public enum BatchStatus {
    /** 开放：可提交复核结论。 */
    OPEN,
    /** 已封板：终态，重复封板幂等成功。 */
    SEALED
}
