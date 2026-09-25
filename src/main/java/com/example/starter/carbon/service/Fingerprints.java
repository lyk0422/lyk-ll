package com.example.starter.carbon.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 申请指纹与凭证号生成工具。指纹覆盖 requestId、企业、履约年度与组合明细。
 */
final class Fingerprints {

    private Fingerprints() {
    }

    /** 数量统一保留3位小数（吨CO2e）后参与指纹与比较。 */
    static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 计算申请指纹 SHA-256 十六进制串，规范化拼接：
     * requestId | 企业编码 | 履约年度 | 配额量(3位小数) | 抵消量(3位小数)。
     */
    static String of(String requestId, String enterpriseCode, int complianceYear,
                     BigDecimal allowanceAmount, BigDecimal offsetAmount) {
        String canonical = String.join("|",
                requestId,
                enterpriseCode,
                Integer.toString(complianceYear),
                normalize(allowanceAmount).toPlainString(),
                normalize(offsetAmount).toPlainString());
        return sha256Hex(canonical);
    }

    /**
     * 凭证号：CRT-年度-申请指纹前24位大写，同 requestId 必然稳定且全局唯一。
     */
    static String certificateNo(int complianceYear, String requestId) {
        return "CRT-" + complianceYear + "-" + sha256Hex(requestId).substring(0, 24).toUpperCase();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
