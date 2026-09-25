package com.example.starter.pv;

/**
 * 严重性分类：决定初报法定时限。
 */
public enum Seriousness {

    /** 致死或危及生命：T0 起 7 日内提交初报。 */
    FATAL_OR_LIFE_THREATENING(7),

    /** 其他严重个例：T0 起 15 日内提交初报。 */
    OTHER_SERIOUS(15);

    private final int reportingDays;

    Seriousness(int reportingDays) {
        this.reportingDays = reportingDays;
    }

    /**
     * 初报时限天数（自获知日 T0 起算）。
     */
    public int reportingDays() {
        return reportingDays;
    }
}
