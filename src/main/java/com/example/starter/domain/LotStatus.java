package com.example.starter.domain;

/**
 * 缸次状态：CREATED 待投料 → FED 已投料 →（出缸判定）PASSED/DOWNGRADED；
 * REPAIRING 回修中；MERGED 已并缸；SPLIT 已分缸只读（仅可查谱系）；ARCHIVED 判定归档终态。
 */
public enum LotStatus {
    CREATED,
    FED,
    REPAIRING,
    PASSED,
    DOWNGRADED,
    MERGED,
    SPLIT,
    ARCHIVED
}
