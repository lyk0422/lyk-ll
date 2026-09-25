package com.example.starter.dye.domain;

/**
 * 配方版本状态。
 */
public enum RecipeStatus {
    /** 草稿：可继续修改 */
    DRAFT,
    /** 已发布：冻结，禁止原地修改，改动须生成新版本 */
    PUBLISHED
}
