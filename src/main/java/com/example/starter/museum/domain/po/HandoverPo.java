package com.example.starter.museum.domain.po;

import com.example.starter.museum.domain.HandoverType;

import java.time.Instant;

/**
 * 点交单行。lenderConfirmed/borrowerConfirmed 双方均为 TRUE 才完成，
 * 单方确认时 completedAt 为 NULL，不产生状态流转。
 */
public class HandoverPo {
    public Long id;
    public Long contractId;
    public HandoverType type;
    public Boolean lenderConfirmed;
    public Boolean borrowerConfirmed;
    public Instant completedAt;
    public Instant createdAt;
    public Instant updatedAt;
}
