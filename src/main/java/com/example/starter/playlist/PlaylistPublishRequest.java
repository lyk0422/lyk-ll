package com.example.starter.playlist;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 播单发布请求：频道 + 可选版本号（为空时自动递增）+ 节目项列表。
 */
public record PlaylistPublishRequest(
        @NotBlank String channelId,
        Integer versionNo,
        @NotEmpty @Valid List<PlaylistItemInput> items
) {
    /**
     * 节目项入参，字段校验在控制器层完成，业务层做时段规则校验。
     */
    public record PlaylistItemInput(
            @NotBlank String itemId,
            @NotBlank String mediaDigest,
            @NotNull Long startMs,
            @NotNull Long endMs
    ) {
    }
}
