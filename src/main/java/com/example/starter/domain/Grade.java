package com.example.starter.domain;

/**
 * 重要用户保电等级（合成分级），数值越小优先级越高。
 */
public enum Grade {
    /** 特级：停电满 5 分钟启动发电车流程 */
    SPECIAL(0),
    /** 一级 */
    FIRST(1),
    /** 二级 */
    SECOND(2),
    /** 普通 */
    NORMAL(3);

    private final int priority;

    Grade(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /**
     * 返回两个等级中更优先（数值更小）的等级，均为空时返回 NORMAL。
     */
    public static Grade higher(Grade a, Grade b) {
        if (a == null) {
            return b == null ? NORMAL : b;
        }
        if (b == null) {
            return a;
        }
        return a.priority <= b.priority ? a : b;
    }
}
