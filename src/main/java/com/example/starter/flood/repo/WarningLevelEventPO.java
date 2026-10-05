package com.example.starter.flood.repo;

import java.time.LocalDateTime;

/**
 * 预警级别变更事件 PO：滚动演算泄流能力不足时自动提级留痕。
 */
public class WarningLevelEventPO {
    public Long id;
    public Long warningId;
    public String fromLevel;
    public String toLevel;
    public String reason;
    /** 触发本次提级的滚动演算主键。 */
    public Long rollingId;
    public LocalDateTime createdAt;
}
