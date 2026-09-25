package com.example.rebate.web.dto;

/**
 * 数据采集响应。
 *
 * @param created {@code true} 新采集；{@code false} 命中去重，已存在
 */
public record CollectResponse(boolean created, String key) {
}
