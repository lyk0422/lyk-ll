package com.example.starter.lc.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.lc.web.dto.DiscrepancyInput;
import com.example.starter.lc.web.dto.DocItem;
import com.example.starter.lc.web.dto.WaiverItem;

/**
 * 合成银行日历与幂等指纹的纯单元测试。
 */
class BankingCalendarAndFingerprintTest {

    @Test
    void deadlineIsFifthBankingDayAt17Utc() {
        // 收到日 2026-06-01（周一）10:00，合成日历次日起第5个工作日 = 06-06 17:00。
        LocalDateTime received = LocalDateTime.parse("2026-06-01T10:00:00");
        assertThat(BankingCalendar.reviewDeadline(received))
                .isEqualTo(LocalDateTime.parse("2026-06-06T17:00:00"));
    }

    @Test
    void noticeBoundaryBeforeAndAtCutoff() {
        LocalDateTime deadline = LocalDateTime.parse("2026-06-06T17:00:00");
        assertThat(BankingCalendar.isNoticeInTime(
                LocalDateTime.parse("2026-06-06T16:59:59"), deadline)).isTrue();
        assertThat(BankingCalendar.isNoticeInTime(
                LocalDateTime.parse("2026-06-06T17:00:00"), deadline)).isFalse();
        assertThat(BankingCalendar.isNoticeInTime(
                LocalDateTime.parse("2026-06-06T17:00:01"), deadline)).isFalse();
    }

    @Test
    void discrepancyOrderNormalizedInFingerprint() {
        FingerprintService fp = new FingerprintService();
        List<DiscrepancyInput> a = List.of(
                new DiscrepancyInput("BILL_OF_LADING", "LATE_SHIPMENT", "desc1"),
                new DiscrepancyInput("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH", "desc2"));
        List<DiscrepancyInput> reversed = List.of(
                new DiscrepancyInput("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH", "desc2"),
                new DiscrepancyInput("BILL_OF_LADING", "LATE_SHIPMENT", "desc1"));
        List<DiscrepancyInput> changed = List.of(
                new DiscrepancyInput("BILL_OF_LADING", "LATE_SHIPMENT", "desc1"),
                new DiscrepancyInput("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH", "DIFFERENT"));

        String hash1 = fp.notice("r1", "P1", a);
        String hash2 = fp.notice("r1", "P1", reversed);
        String hash3 = fp.notice("r1", "P1", changed);
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(hash3);
    }

    @Test
    void presentFingerprintCoversAllBusinessFields() {
        FingerprintService fp = new FingerprintService();
        List<DocItem> docs = List.of(new DocItem("BILL_OF_LADING", "BL-1", "x"));
        String base = fp.present("r1", "LC-1", "P-1", null, "100.00", docs);
        assertThat(fp.present("r1", "LC-1", "P-1", null, "100.00", docs)).isEqualTo(base);
        assertThat(fp.present("r1", "LC-1", "P-1", null, "100.01", docs)).isNotEqualTo(base);
        assertThat(fp.present("r1", "LC-1", "P-1", null, "100.00",
                List.of(new DocItem("BILL_OF_LADING", "BL-2", "x")))).isNotEqualTo(base);
        assertThat(fp.present("r2", "LC-1", "P-1", null, "100.00", docs)).isNotEqualTo(base);
    }

    @Test
    void waiverFingerprintOrderInsensitiveButContentSensitive() {
        FingerprintService fp = new FingerprintService();
        List<WaiverItem> a = List.of(
                new WaiverItem("BILL_OF_LADING", "LATE_SHIPMENT"),
                new WaiverItem("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH"));
        List<WaiverItem> reversed = List.of(
                new WaiverItem("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH"),
                new WaiverItem("BILL_OF_LADING", "LATE_SHIPMENT"));
        List<WaiverItem> partial = List.of(new WaiverItem("BILL_OF_LADING", "LATE_SHIPMENT"));
        assertThat(fp.waiver("r1", "P1", a)).isEqualTo(fp.waiver("r1", "P1", reversed));
        assertThat(fp.waiver("r1", "P1", partial))
                .isNotEqualTo(fp.waiver("r1", "P1", a));
    }
}
