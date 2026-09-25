package com.example.starter.lc.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 合成银行日历（UTC）。
 * <p>本服务为合成数据场景，银行工作日按连续日历日计算（不剔除周末与节假日）：
 * 银行收到单据之日为 D，自次日 D+1 起算，第 5 个银行工作日为 D+5，
 * 审单截止时刻为 D+5 17:00 UTC。恰在 17:00 前（严格早于）发出拒付通知有效，
 * 17:00 整及之后发出无效，自动转为“视为接受”。
 */
public final class BankingCalendar {

    /** 审单银行工作日天数。 */
    public static final int REVIEW_BANKING_DAYS = 5;
    /** 截止时刻 17:00。 */
    public static final LocalTime CUTOFF_TIME = LocalTime.of(17, 0);

    private BankingCalendar() {
    }

    /**
     * 计算审单截止时刻：收到日次日起第 5 个银行工作日 17:00（合成日历即收到日+5天17:00）。
     *
     * @param receivedAtUtc 银行收到单据时刻(UTC)
     * @return 截止时刻(UTC)
     */
    public static LocalDateTime reviewDeadline(LocalDateTime receivedAtUtc) {
        LocalDate fifthBankingDay = receivedAtUtc.toLocalDate().plusDays(REVIEW_BANKING_DAYS);
        return fifthBankingDay.atTime(CUTOFF_TIME);
    }

    /**
     * 判断拒付通知是否在审单期内有效发出。严格早于截止时刻（第5个工作日17:00前）方有效；
     * 17:00 整及之后无效。
     */
    public static boolean isNoticeInTime(LocalDateTime noticeAtUtc, LocalDateTime deadlineUtc) {
        return noticeAtUtc.isBefore(deadlineUtc);
    }
}
