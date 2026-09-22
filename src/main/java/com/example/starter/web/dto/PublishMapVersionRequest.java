package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 地图版本发布请求；发布即不可变，热更新只能再发布新版本。
 *
 * @param versionId 地图版本业务标识，全局唯一
 * @param segments  版本内全部路段，至少一条
 */
public record PublishMapVersionRequest(
        @NotBlank String versionId,
        @NotEmpty List<SegmentDto> segments) {
}
