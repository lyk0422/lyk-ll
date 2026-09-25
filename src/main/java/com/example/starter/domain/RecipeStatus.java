package com.example.starter.domain;

/**
 * 配方版本状态。
 */
public enum RecipeStatus {
    /** 草稿：可继续编辑，尚未冻结。 */
    DRAFT,
    /** 已发布：冻结，不可修改，改动只能生成新版本。 */
    PUBLISHED
}
