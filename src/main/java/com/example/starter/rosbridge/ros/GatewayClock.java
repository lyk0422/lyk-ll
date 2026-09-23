package com.example.starter.rosbridge.ros;

import org.springframework.stereotype.Component;

/**
 * 可控时钟，业务逻辑统一取这里的时间，测试中可拨快以验证宽限期与超时。
 */
@Component
public class GatewayClock {

    /** null 表示跟随系统时钟；非 null 时为测试设定的虚拟时间（UTC 纪元毫秒，可为 0）。 */
    private volatile Long fixedMillis = null;

    public long nowMillis() {
        Long fixed = fixedMillis;
        return fixed != null ? fixed : System.currentTimeMillis();
    }

    /** 测试用：设定虚拟当前时间。 */
    public void setFixedMillis(long millis) {
        this.fixedMillis = millis;
    }

    /** 测试用：在虚拟时间轴上推进。 */
    public void advance(long deltaMillis) {
        Long fixed = fixedMillis;
        this.fixedMillis = (fixed != null ? fixed : System.currentTimeMillis()) + deltaMillis;
    }

    /** 测试用：恢复系统时钟。 */
    public void reset() {
        this.fixedMillis = null;
    }
}
