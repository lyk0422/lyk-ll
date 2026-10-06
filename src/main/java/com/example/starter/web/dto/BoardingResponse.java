package com.example.starter.web.dto;

/**
 * 上客响应。
 *
 * @param boardingId 上客记录 id
 * @param lineId     线路 id
 * @param accepted   是否允许上客
 */
public record BoardingResponse(long boardingId, long lineId, boolean accepted) {
}
