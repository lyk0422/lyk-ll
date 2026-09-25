package com.example.starter.domain;

/**
 * 列车作业行(对应 train_operation 表)。
 *
 * @param id               作业主键
 * @param trackNo          股道号
 * @param trainNo          车次
 * @param setPressureKpa   制动管定压(kPa)
 * @param status           作业状态
 * @param consistVersion   当前编组版本号
 * @param recoveryPending  感度合格后是否等待风压恢复定压
 * @param recoveryOk       风压是否已恢复至定压(恰达定压为恢复)
 */
public record OperationRow(long id, String trackNo, String trainNo, int setPressureKpa,
                           OperationStatus status, int consistVersion,
                           boolean recoveryPending, boolean recoveryOk) {
}
