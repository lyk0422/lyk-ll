package com.example.starter.museum.domain.po;

import com.example.starter.museum.domain.AlertStatus;

import java.time.Instant;

/**
 * 环境异常行。连续 3 个采样点越限即触发（恰 3 个）。
 * status：OPEN→URGENT（满 24h 未处置）→AWAIT_REVIEW（环境恢复）→RESOLVED（双方复核）。
 */
public class EnvironmentAlertPo {
    public Long id;
    public String alertNo;
    public Long showcaseId;
    public Long contractId;
    public Long triggerReadingId;
    public Instant triggerTime;
    public AlertStatus status;
    public Instant createdAt;
    public Instant escalatedAt;
    public Instant recoveredAt;
    public Instant resolvedAt;
}
