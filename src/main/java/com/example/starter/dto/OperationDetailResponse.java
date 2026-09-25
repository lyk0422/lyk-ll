package com.example.starter.dto;

import com.example.starter.domain.OperationStatus;

/**
 * 作业详情响应。
 */
public record OperationDetailResponse(long id, String trackNo, String trainNo, int setPressureKpa,
                                      OperationStatus status, int consistVersion,
                                      int carCount, long cutoutCount) {
}
