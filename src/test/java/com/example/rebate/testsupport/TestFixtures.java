package com.example.rebate.testsupport;

/**
 * 测试公共常量与 JSON 片段。
 */
public final class TestFixtures {

    public static final String P1 = "P-ELECTRONIC-01";

    private TestFixtures() {
    }

    /**
     * 登记两个文库版本：V2025(2025-01-01, 10%)、V2026(2026-01-01, 13%)。
     */
    public static String libraryV2025(String requestId) {
        return """
                {"requestId":"%s","productCode":"%s","versionNo":"V2025",
                 "effectiveFrom":"2025-01-01","rebateRate":0.10}
                """.formatted(requestId, P1);
    }

    public static String libraryV2026(String requestId) {
        return """
                {"requestId":"%s","productCode":"%s","versionNo":"V2026",
                 "effectiveFrom":"2026-01-01","rebateRate":0.13}
                """.formatted(requestId, P1);
    }

    public static String customs(String requestId, String no, String product,
                                 String fob, String fobUsd) {
        return """
                {"requestId":"%s","declarationNo":"%s","productCode":"%s",
                 "fobAmount":%s,"fobUsd":%s}
                """.formatted(requestId, no, product, fob, fobUsd);
    }

    public static String invoice(String requestId, String no, String declarationNo,
                                 String product, String amount) {
        return """
                {"requestId":"%s","invoiceNo":"%s","declarationNo":"%s",
                 "productCode":"%s","amount":%s}
                """.formatted(requestId, no, declarationNo, product, amount);
    }

    public static String check(String requestId, String batchNo,
                               String declareDate, String... declarationNos) {
        String array = String.join(",", java.util.Arrays.stream(declarationNos)
                .map(s -> "\"" + s + "\"").toList());
        return """
                {"requestId":"%s","batchNo":"%s","declarationNos":[%s],"declareDate":"%s"}
                """.formatted(requestId, batchNo, array, declareDate);
    }

    public static String requestId(String requestId) {
        return "{\"requestId\":\"" + requestId + "\"}";
    }

    public static String declare(String requestId, boolean reviewConfirmed) {
        return "{\"requestId\":\"%s\",\"reviewConfirmed\":%s}"
                .formatted(requestId, reviewConfirmed);
    }

    public static String explain(String requestId, String text) {
        return "{\"requestId\":\"%s\",\"explanation\":\"%s\"}".formatted(requestId, text);
    }

    public static String review(String requestId) {
        return "{\"requestId\":\"%s\",\"confirmed\":true}".formatted(requestId);
    }

    public static String treasury(String requestId, String serial, String received) {
        return "{\"requestId\":\"%s\",\"treasurySerial\":\"%s\",\"receivedAmount\":%s}"
                .formatted(requestId, serial, received);
    }

    public static String late(String requestId, String decl, String inv, String note) {
        return "{\"requestId\":\"%s\",\"declarationNo\":\"%s\",\"invoiceNo\":\"%s\",\"note\":\"%s\"}"
                .formatted(requestId, decl, inv, note);
    }

    /**
     * 冲回提交（金额为正数整数分，occurredAt 为 UTC 精确时刻，如 2026-03-01T10:00:00Z）。
     */
    public static String reversalSubmit(String requestId, String declarationNo, String correctionNo,
                                        String reason, long amountCents, String submitter,
                                        int expectedVersion, String occurredAt) {
        return """
                {"requestId":"%s","declarationNo":"%s","correctionNo":"%s","reason":%s,
                 "amountCents":%d,"submitter":"%s","expectedVersion":%d,"occurredAt":"%s"}
                """.formatted(requestId, declarationNo, correctionNo, jsonText(reason),
                amountCents, submitter, expectedVersion, occurredAt);
    }

    /**
     * 冲回复核：action=APPROVE/REJECT。
     */
    public static String reversalReview(String requestId, String reviewer, String action,
                                        int expectedVersion, String occurredAt) {
        return """
                {"requestId":"%s","reviewer":"%s","action":"%s","expectedVersion":%d,
                 "occurredAt":"%s"}
                """.formatted(requestId, reviewer, action, expectedVersion, occurredAt);
    }

    /**
     * 追缴到账回执。
     */
    public static String clawbackReceipt(String requestId, String receiptSerial, long amountCents,
                                         int expectedVersion, String occurredAt) {
        return """
                {"requestId":"%s","receiptSerial":"%s","amountCents":%d,"expectedVersion":%d,
                 "occurredAt":"%s"}
                """.formatted(requestId, receiptSerial, amountCents, expectedVersion, occurredAt);
    }

    /** 把任意字符串编码为 JSON 文本字面量（含分隔符/引号的合法文本）。 */
    public static String jsonText(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
