package com.example.starter.dispatch.support;

import java.time.Instant;

/**
 * 竞态测试专用时钟：基础行为与 {@link MutableClock} 一致（固定/拨表）；
 * 另支持按线程角色返回不同事件时刻——回执线程在执行时限点到达，
 * 扫描线程在时限后 1 毫秒触发，两者并发竞争，终态由数据库行锁裁决。
 */
public class RaceClock extends MutableClock {

    public enum Role { RECEIPT, SCAN }

    private final ThreadLocal<Role> role = ThreadLocal.withInitial(() -> null);
    private volatile Instant raceDeadline;

    public void beginRace(Instant deadline) {
        this.raceDeadline = deadline;
    }

    public void asRole(Role r) {
        role.set(r);
    }

    public void clearRole() {
        role.remove();
    }

    @Override
    public Instant instant() {
        Role r = role.get();
        if (r != null && raceDeadline != null) {
            return r == Role.RECEIPT ? raceDeadline : raceDeadline.plusMillis(1);
        }
        return super.instant();
    }
}
