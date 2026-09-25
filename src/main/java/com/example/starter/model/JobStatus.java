package com.example.starter.model;

/**
 * 通风作业状态；归档为终态。
 */
public enum JobStatus {
    /** 执行中 */
    EXECUTING,
    /** 已完成（通风结束，待复测/归档） */
    COMPLETED,
    /** 已归档（终态） */
    ARCHIVED
}
