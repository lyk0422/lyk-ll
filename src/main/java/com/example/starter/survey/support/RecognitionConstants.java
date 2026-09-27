package com.example.starter.survey.support;

/**
 * 互认模块合成常量；全部为虚构值，不代表任何真实船旗国机构。
 */
public final class RecognitionConstants {

    /** 唯一接受的合成签认标记；不匹配的标记一律拒绝，不访问真实机构。 */
    public static final String SYNTHETIC_SIGNATURE_MARKER = "SYNTH-FLAG-RECOGNITION/v1";

    /** 绑定失效原因：引用的报告修订已被更正替代。 */
    public static final String REASON_REPORT_REVISED = "REPORT_REVISED";

    /** 风险原因：更正涉及已签发检验使用的历史证据。 */
    public static final String REASON_CERTIFIED_HISTORY_REVISED = "CERTIFIED_HISTORY_REVISED";

    private RecognitionConstants() {
    }
}
