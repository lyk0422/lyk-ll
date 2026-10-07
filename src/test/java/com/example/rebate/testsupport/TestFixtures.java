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

    public static String chargebackSubmit(String requestId, String declNo, String correctionNo,
                                          String reason, long amountCents, String submitter) {
        return """
                {"requestId":"%s","declarationNo":"%s","correctionNo":"%s","reason":"%s",
                 "amountCents":%d,"submitter":"%s"}
                """.formatted(requestId, declNo, correctionNo, reason, amountCents, submitter);
    }

    public static String chargebackReview(String requestId, int expectedVersion,
                                          String action, String reviewer) {
        return """
                {"requestId":"%s","expectedVersion":%d,"action":"%s","reviewer":"%s"}
                """.formatted(requestId, expectedVersion, action, reviewer);
    }

    public static String receipt(String requestId, int expectedVersion, String serial,
                                 long receivedCents, String receivedAt) {
        return """
                {"requestId":"%s","expectedVersion":%d,"receiptSerial":"%s",
                 "receivedCents":%d,"receivedAt":"%s"}
                """.formatted(requestId, expectedVersion, serial, receivedCents, receivedAt);
    }

    /**
     * 汇总到账分配请求：母流水、总额、到账时刻与分配行 JSON 数组。
     */
    public static String batchReceipt(String requestId, String bankSerial, long totalCents,
                                      String receivedAt, String allocationsJson) {
        return """
                {"requestId":"%s","bankSerial":"%s","totalCents":%d,"receivedAt":"%s",
                 "allocations":%s}
                """.formatted(requestId, bankSerial, totalCents, receivedAt, allocationsJson);
    }

    /**
     * 汇总分配行：目标更正编号、分摊金额与目标追缴明细期望版本。
     */
    public static String allocation(String correctionNo, long amountCents, int expectedVersion) {
        return """
                {"correctionNo":"%s","amountCents":%d,"expectedVersion":%d}
                """.formatted(correctionNo, amountCents, expectedVersion);
    }
}
