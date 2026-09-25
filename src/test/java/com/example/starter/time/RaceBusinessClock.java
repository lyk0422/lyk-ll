package com.example.starter.time;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 并发竞态专用时钟（非 Spring 组件，由竞态测试的 TestConfiguration 以主 Bean 提供）：
 * 为打了标签的线程返回不同固定时刻，模拟“异议临界前一刻”与“期满生效时刻”
 * 两个操作真正同时提交，由数据库行锁决定唯一终态。
 */
public class RaceBusinessClock implements BusinessClock {

    private final ThreadLocal<String> tag = new ThreadLocal<>();
    private final Map<String, Instant> times = new HashMap<>();
    private volatile Instant fallback = Instant.now();

    /** 当前线程设置标签，传 null 清除。 */
    public void tagCurrentThread(String threadTag) {
        if (threadTag == null) {
            tag.remove();
        } else {
            tag.set(threadTag);
        }
    }

    /** 设定某标签线程读取到的时刻。 */
    public void setTime(String threadTag, Instant instant) {
        synchronized (times) {
            times.put(threadTag, instant);
        }
    }

    /** 未打标签线程读取的默认时刻。 */
    public void setFallback(Instant instant) {
        this.fallback = instant;
    }

    @Override
    public Instant now() {
        String threadTag = tag.get();
        if (threadTag == null) {
            return fallback;
        }
        synchronized (times) {
            return times.getOrDefault(threadTag, fallback);
        }
    }
}
