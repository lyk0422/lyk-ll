package com.example.starter.trial.domain;

/**
 * 业务常量：组别、盲态编码、状态、审计事件类型。
 */
public final class TrialConstants {

    private TrialConstants() {
    }

    /** 默认合成试验编码（本地演示与测试使用，数据全部合成）。 */
    public static final String DEFAULT_TRIAL = "TRIAL-001";

    /** 区组长度固定为 4，两组 1:1。 */
    public static final int BLOCK_SIZE = 4;

    public static final String GROUP_TREATMENT = "TREATMENT";
    public static final String GROUP_CONTROL = "CONTROL";

    public static final String BLIND_A = "BLIND-A";
    public static final String BLIND_B = "BLIND-B";

    public static final String STATUS_ENROLLED = "ENROLLED";
    public static final String STATUS_UNBLINDED = "UNBLINDED";

    public static final String EVENT_FIRST_CONFIRMED = "UNBLIND_FIRST_CONFIRMED";
    public static final String EVENT_SECOND_CONFIRMED = "UNBLIND_SECOND_CONFIRMED";
    public static final String EVENT_EFFECTIVE = "UNBLIND_EFFECTIVE";
}
