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

    /** 冲回提交（金额为正整数分）。 */
    public static String reversalSubmit(String requestId, String decl, String correctionNo,
                                        String reason, long amountCents, String submittedBy) {
        return """
                {"requestId":"%s","declarationNo":"%s","correctionNo":"%s","reason":"%s",
                 "amountCents":%d,"submittedBy":"%s"}
                """.formatted(requestId, decl, correctionNo, reason, amountCents, submittedBy);
    }

    /** 冲回复核（expectedVersion 从 1 起）。 */
    public static String reversalReview(String requestId, int expectedVersion,
                                        String reviewedBy, boolean approved, String rejectReason) {
        String reasonPart = rejectReason == null ? "null" : "\"" + rejectReason + "\"";
        return """
                {"requestId":"%s","expectedVersion":%d,"reviewedBy":"%s",
                 "approved":%s,"rejectReason":%s}
                """.formatted(requestId, expectedVersion, reviewedBy, approved, reasonPart);
    }

    /** 追缴回执登记。 */
    public static String reversalReceipt(String requestId, int expectedVersion, String serial,
                                         long amountCents, String occurredAt) {
        return """
                {"requestId":"%s","expectedVersion":%d,"receiptSerial":"%s",
                 "amountCents":%d,"occurredAt":"%s"}
                """.formatted(requestId, expectedVersion, serial, amountCents, occurredAt);
    }
}
