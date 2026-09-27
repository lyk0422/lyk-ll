package com.example.starter.web.dto;

/**
 * 调度单冻结的重要用户负荷清单条目。
 *
 * @param userName 重要用户名称
 * @param loadKw   冻结的保电负荷，单位kW
 */
public record DispatchLoadView(
        String userName,
        int loadKw) {
}
