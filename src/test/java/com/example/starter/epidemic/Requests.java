package com.example.starter.epidemic;

import com.example.starter.epidemic.api.ArchiveRequest;
import com.example.starter.epidemic.api.CorrectionRequest;
import com.example.starter.epidemic.api.InitialReportRequest;
import com.example.starter.epidemic.api.ReviewRequest;
import com.example.starter.epidemic.api.SpecialConfirmRequest;
import com.example.starter.epidemic.api.SpecialCorrectionRequest;
import com.example.starter.epidemic.api.WithdrawRequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 测试请求构造工具，全部使用合成数据。
 */
public final class Requests {

    public static final String FACILITY = "F001";
    public static final LocalDate ONSET = LocalDate.of(2026, 9, 24);

    private Requests() {
    }

    public static String rid() {
        return "REQ-" + UUID.randomUUID();
    }

    public static InitialReportRequest initial(String requestId, String patientId, String disease,
                                               String diagnosisType, OffsetDateTime diagnosisTime,
                                               String lateReason) {
        return new InitialReportRequest(requestId, patientId, "合成患者-" + patientId,
                disease, diagnosisType, ONSET, diagnosisTime, FACILITY, "张报告",
                lateReason, null);
    }

    public static InitialReportRequest initialWithOnset(String requestId, String patientId, String disease,
                                                        String diagnosisType, OffsetDateTime diagnosisTime,
                                                        LocalDate onsetDate, String lateReason) {
        return new InitialReportRequest(requestId, patientId, "合成患者-" + patientId,
                disease, diagnosisType, onsetDate, diagnosisTime, FACILITY, "张报告",
                lateReason, null);
    }

    public static CorrectionRequest correction(String requestId, String cardNumber, String disease,
                                               String diagnosisType, OffsetDateTime diagnosisTime,
                                               String lateReason, String notes) {
        return new CorrectionRequest(requestId, cardNumber, disease, diagnosisType,
                ONSET, diagnosisTime, "李订正", lateReason, notes);
    }

    public static CorrectionRequest correctionWithOnset(String requestId, String cardNumber, String disease,
                                                        String diagnosisType, OffsetDateTime diagnosisTime,
                                                        LocalDate onsetDate, String lateReason, String notes) {
        return new CorrectionRequest(requestId, cardNumber, disease, diagnosisType,
                onsetDate, diagnosisTime, "李订正", lateReason, notes);
    }

    public static ReviewRequest review(String reviewer) {
        return new ReviewRequest(reviewer, "同意");
    }

    public static WithdrawRequest withdraw(String operator) {
        return new WithdrawRequest(operator, "报告信息需重新核实");
    }

    public static ArchiveRequest archive(String reviewer) {
        return new ArchiveRequest(reviewer, "终审通过");
    }

    public static SpecialCorrectionRequest special(String requestId, String cardNumber, String disease,
                                                   String diagnosisType, OffsetDateTime diagnosisTime,
                                                   String lateReason) {
        return new SpecialCorrectionRequest(requestId, cardNumber, disease, diagnosisType,
                ONSET, diagnosisTime, lateReason, "疫情复核确需变更");
    }

    public static SpecialConfirmRequest confirm(String reviewer) {
        return new SpecialConfirmRequest(reviewer, "情况属实");
    }

    public static OffsetDateTime utc(int y, int m, int d, int h, int min) {
        return OffsetDateTime.of(y, m, d, h, min, 0, 0, ZoneOffset.UTC);
    }
}
