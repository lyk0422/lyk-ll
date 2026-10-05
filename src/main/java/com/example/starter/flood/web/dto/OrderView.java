package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 泄流指令链视图，含各环节时刻与回执偏差；planVersionNo 为创建时的方案版本快照，
 * 新方案版本不溯及在途指令。
 */
public record OrderView(
        String orderNo,
        String planNo,
        Integer planVersionNo,
        String status,
        LocalDateTime issuedAt,
        LocalDateTime signDeadline,
        LocalDateTime signedAt,
        String signer,
        LocalDateTime escalatedAt,
        String escalatedTo,
        LocalDateTime executedAt,
        String executor,
        LocalDateTime receiptedAt,
        List<OpeningItem> actualOpenings,
        BigDecimal actualTotalRelease,
        BigDecimal deviationRatio,
        String deviationReason,
        boolean replay) {
}
