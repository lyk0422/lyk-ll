package com.example.starter.lc.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹单元测试：不符点/单据按类型排序规范化，顺序不同指纹一致，参数不同指纹不同。
 */
class FingerprintTest {

    @Test
    void discrepanciesSortedByDocTypeProduceSameFingerprint() {
        List<DiscrepancyItem> orderA = List.of(
                new DiscrepancyItem("BILL_OF_LADING", "装船日期晚于信用证规定"),
                new DiscrepancyItem("COMMERCIAL_INVOICE", "金额超过信用证额度"));
        List<DiscrepancyItem> orderB = List.of(
                new DiscrepancyItem("COMMERCIAL_INVOICE", "金额超过信用证额度"),
                new DiscrepancyItem("BILL_OF_LADING", "装船日期晚于信用证规定"));
        Instant refusedAt = Instant.parse("2026-09-24T10:00:00Z");
        assertThat(Fingerprint.forRefuse("LC-1-1", refusedAt, orderA))
                .isEqualTo(Fingerprint.forRefuse("LC-1-1", refusedAt, orderB));
    }

    @Test
    void documentsSortedProduceSameFingerprint() {
        List<DocumentItem> orderA = List.of(
                new DocumentItem("BILL_OF_LADING", "BL-001"),
                new DocumentItem("COMMERCIAL_INVOICE", "INV-009"));
        List<DocumentItem> orderB = List.of(
                new DocumentItem("COMMERCIAL_INVOICE", "INV-009"),
                new DocumentItem("BILL_OF_LADING", "BL-001"));
        Instant presentedAt = Instant.parse("2026-09-18T10:00:00Z");
        assertThat(Fingerprint.forPresent("LC-1", new BigDecimal("8000.00"), presentedAt, orderA))
                .isEqualTo(Fingerprint.forPresent("LC-1", new BigDecimal("8000.00"), presentedAt, orderB));
    }

    @Test
    void differentBusinessFieldsProduceDifferentFingerprint() {
        Instant t = Instant.parse("2026-09-18T10:00:00Z");
        String base = Fingerprint.forPresent("LC-1", new BigDecimal("8000.00"), t,
                List.of(new DocumentItem("BILL_OF_LADING", "BL-001")));
        assertThat(Fingerprint.forPresent("LC-2", new BigDecimal("8000.00"), t,
                List.of(new DocumentItem("BILL_OF_LADING", "BL-001")))).isNotEqualTo(base);
        assertThat(Fingerprint.forPresent("LC-1", new BigDecimal("8000.01"), t,
                List.of(new DocumentItem("BILL_OF_LADING", "BL-001")))).isNotEqualTo(base);
        assertThat(Fingerprint.forPresent("LC-1", new BigDecimal("8000.00"), t.plusSeconds(1),
                List.of(new DocumentItem("BILL_OF_LADING", "BL-001")))).isNotEqualTo(base);
    }

    @Test
    void bigDecimalScaleIsNormalized() {
        Instant t = Instant.parse("2026-09-18T10:00:00Z");
        List<DocumentItem> docs = List.of(new DocumentItem("BILL_OF_LADING", "BL-001"));
        assertThat(Fingerprint.forPresent("LC-1", new BigDecimal("8000.00"), t, docs))
                .isEqualTo(Fingerprint.forPresent("LC-1", new BigDecimal("8000"), t, docs));
    }

    @Test
    void createLcFingerprintCoversAllFields() {
        LocalDate expiry = LocalDate.of(2026, 12, 31);
        String base = Fingerprint.forCreateLc("LC-1", "Applicant", "Beneficiary", "USD",
                new BigDecimal("10000"), expiry);
        assertThat(Fingerprint.forCreateLc("LC-1", "Applicant", "Beneficiary", "EUR",
                new BigDecimal("10000"), expiry)).isNotEqualTo(base);
        assertThat(Fingerprint.forCreateLc("LC-1", "Applicant", "Beneficiary", "USD",
                new BigDecimal("10000"), LocalDate.of(2026, 11, 30))).isNotEqualTo(base);
    }
}
