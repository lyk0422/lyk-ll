package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 处置链 PO。
 */
public class WarningPO {
    public Long id;
    public String warningNo;
    public Long pondId;
    public String title;
    public String rainSummary;
    /** ACTIVE / ARCHIVED。 */
    public String status;
    /** 预警级别：BLUE / YELLOW / ORANGE / RED，泄流能力不足时自动逐级提升。 */
    public String warningLevel;
    public String requestId;
    public LocalDateTime openedAt;
    public LocalDateTime archivedAt;
}
