package com.example.starter.playout.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 播单发布请求。
 *
 * @param channel 频道标识
 * @param version 调用方提供的播单版本号（同频道唯一）
 * @param items   节目项列表（至少一项；itemId 唯一；半开时段不重叠）
 */
public record PlaylistPublishRequest(
        @NotBlank String channel,
        @NotBlank String version,
        @NotEmpty @Valid List<PlaylistItemRequest> items) {
}
