package com.example.starter.lc.support;

import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 幂等指纹：覆盖除 requestId 外的全部业务字段；不符点与单据按类型排序规范化，集合顺序不同不产生异参。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String forCreateLc(String lcNo, String applicant, String beneficiary, String currency,
                                     BigDecimal totalAmount, LocalDate expiryDate) {
        return canonical("CREATE_LC", List.of(
                s(lcNo), s(applicant), s(beneficiary), s(currency), bd(totalAmount), ld(expiryDate)));
    }

    public static String forPresent(String lcNo, BigDecimal drawAmount, Instant presentedAt,
                                    List<DocumentItem> documents) {
        List<String> sortedDocs = documents.stream()
                .map(d -> kv("doc", List.of(s(d.docType()), s(d.docRef()))))
                .sorted()
                .toList();
        return canonical("PRESENT", List.of(s(lcNo), bd(drawAmount), instant(presentedAt), list(sortedDocs)));
    }

    public static String forRefuse(String batchNo, Instant refusedAt, List<DiscrepancyItem> discrepancies) {
        List<String> sortedDisc = discrepancies.stream()
                .map(d -> kv("disc", List.of(s(d.docType()), s(d.description()))))
                .sorted()
                .toList();
        return canonical("REFUSE", List.of(s(batchNo), instant(refusedAt), list(sortedDisc)));
    }

    public static String forWaive(String batchNo, String applicant, Instant waivedAt,
                                  List<DiscrepancyItem> discrepancies) {
        List<String> sortedDisc = discrepancies.stream()
                .map(d -> kv("disc", List.of(s(d.docType()), s(d.description()))))
                .sorted()
                .toList();
        return canonical("WAIVE", List.of(s(batchNo), s(applicant), instant(waivedAt), list(sortedDisc)));
    }

    public static String forPay(String batchNo, Instant paidAt) {
        return canonical("PAY", List.of(s(batchNo), instant(paidAt)));
    }

    private static String canonical(String op, List<String> parts) {
        return sha256(op + "|" + String.join("|", parts));
    }

    private static String kv(String key, List<String> values) {
        return key + "={" + String.join(";", values) + "}";
    }

    private static String list(List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    private static String s(String value) {
        return Objects.toString(value, "");
    }

    private static String bd(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String ld(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }
}
