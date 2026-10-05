package com.example.starter.flood.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 入库洪水过程线录入请求；全部点进入幂等指纹，同键异参 409。
 *
 * @param requestId 幂等键
 * @param points 过程线点（预报时刻 + 入库流量），可同时刻重复（后报覆盖先报）
 */
public record HydrographRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<HydrographPointItem> points) {
}
