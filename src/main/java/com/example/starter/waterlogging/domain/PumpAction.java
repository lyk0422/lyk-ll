package com.example.starter.waterlogging.domain;

/**
 * 泵站启停动作与机组状态常量。
 */
public final class PumpAction {

    /** 启机。 */
    public static final String START = "START";
    /** 停机。 */
    public static final String STOP = "STOP";

    /** 机组运行中。 */
    public static final String RUNNING = "RUNNING";
    /** 机组已停机。 */
    public static final String STOPPED = "STOPPED";

    private PumpAction() {
    }
}
