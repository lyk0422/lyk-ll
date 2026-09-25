package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 对外接口响应报文。
 */
public final class ApiResponses {

    private ApiResponses() {
    }

    /** 出港申报受理结果。 */
    public record DepartureResponse(Long voyageId, String voyageNo, String status) {
    }

    /** 日志按“船＋日期＋渔区”归位后的分组结果。 */
    public record LogGroup(Long logId, LocalDate logDate, String fishingArea, boolean bycatchFlagged) {
    }

    /** 渔捞日志受理结果；late 为 true 表示航次已结案，仅记录迟到事件并转审查。 */
    public record LogbookResponse(Long voyageId, String status, boolean late, List<LogGroup> logs) {
    }

    /** 进港报告受理结果；deviationFlagged 为 true 时已触发人工复核并冻结配额余额。 */
    public record ArrivalResponse(Long voyageId, String status, boolean deviationFlagged, Long reviewId) {
    }

    /** 配额核销结果，items 为实际扣减明细。 */
    public record SettlementResponse(Long voyageId, String status, List<ApiRequests.SpeciesQuantity> items) {
    }

    /** 结案结果，重复结案返回相同的幂等成功结果。 */
    public record ClosureResponse(Long voyageId, String status, Instant closedAt) {
    }

    /** 复核办结结果。 */
    public record ReviewResponse(Long reviewId, String status, String resolution) {
    }

    /** 配额台账条目，remaining = totalQuota - usedQuota，可复算。 */
    public record LedgerEntry(String speciesCode, int quotaYear, BigDecimal totalQuota,
                              BigDecimal usedQuota, BigDecimal remaining, boolean frozen) {
    }

    /** 统一错误报文。 */
    public record ErrorResponse(String code, String message) {
    }
}
