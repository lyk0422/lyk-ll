package com.example.starter.dye.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 基于已发布冻结版本派生新版本的修改请求；旧版本原样保留。
 * 名称/浴比省略时沿用旧版本；条目为全量覆盖。
 */
public record RecipeNewVersionRequest(
        String name,
        Integer liquorRatio,
        @NotEmpty @Valid List<RecipeItemRequest> items) {
}
