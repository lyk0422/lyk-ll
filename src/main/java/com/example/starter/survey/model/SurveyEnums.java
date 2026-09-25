package com.example.starter.survey.model;

/**
 * 船级检验领域枚举集合。所有枚举统一按 {@code name()} 持久化为字符串。
 */
public final class SurveyEnums {

    private SurveyEnums() {
    }

    /** 检验类型：ANNUAL 年度（可展期）/ INTERMEDIATE 中间 / SPECIAL 特别。 */
    public enum SurveyType {
        ANNUAL,
        INTERMEDIATE,
        SPECIAL
    }

    /** 检验单状态：申请→登轮→整改→发证。 */
    public enum SurveyStatus {
        /** 已申请，尚未登轮。 */
        APPLIED,
        /** 已登轮检验（无缺陷或仅有待复查一般缺陷时也可能停留在该态）。 */
        BOARDING,
        /** 存在缺陷整改中。 */
        RECTIFYING,
        /** 已发证（终态）。 */
        ISSUED
    }

    /** 缺陷严重程度。 */
    public enum Severity {
        /** 重大缺陷：未整改不得发证。 */
        MAJOR,
        /** 一般缺陷：限期 30 日复查。 */
        GENERAL
    }

    /** 缺陷状态。 */
    public enum DefectStatus {
        /** 待整改。 */
        OPEN,
        /** 已整改，待复查受理（一般缺陷）；重大缺陷整改后直接 ACCEPTED。 */
        RECTIFIED,
        /** 复查/整改已受理。 */
        ACCEPTED,
        /** 复查逾期。 */
        EXPIRED
    }

    /** 证书状态。 */
    public enum CertificateStatus {
        /** 有效。 */
        VALID,
        /** 被新版本证书替代。 */
        INVALID,
        /** 一般缺陷复查逾期失效。 */
        EXPIRED
    }

    /** 展期申请状态。 */
    public enum ExtensionStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    /** 幂等操作类型，指纹计算时一并入签，防止跨操作串用 requestId。 */
    public enum Operation {
        APPLY_SURVEY,
        BOARD_SURVEY,
        RECTIFY_DEFECT,
        RECHECK_DEFECT,
        ISSUE_CERTIFICATE,
        APPLY_EXTENSION,
        APPROVE_EXTENSION,
        LATE_RECORD,
        RECOMPUTE
    }

    /** 窗口台账状态。 */
    public enum LedgerState {
        /** 窗口内无检验。 */
        EMPTY,
        /** 检验进行中。 */
        PENDING,
        /** 在窗口内（含首末日、含展期）完成登轮。 */
        IN_WINDOW,
        /** 在窗口外完成。 */
        OUT_OF_WINDOW
    }
}
