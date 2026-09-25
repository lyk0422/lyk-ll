package com.example.starter.time;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 默认可控时钟：生产环境返回系统当前时刻，测试可显式设定/推进。
 *
 * <p>另提供“按线程标签返回时刻”的测试能力：并发竞态测试可为不同线程分别指定时刻，
 * 未打标签的生产/普通测试路径不受影响。
 */
@Component
public class MutableBusinessClock implements BusinessClock {

    private volatile Instant fixed;
    private final ThreadLocal<String> threadTag = new ThreadLocal<>();
    private final Map<String, Instant> taggedTimes = new HashMap<>();

    @Override
    public Instant now() {
        String tag = threadTag.get();
        if (tag != null) {
            synchronized (taggedTimes) {
                Instant tagged = taggedTimes.get(tag);
                if (tagged != null) {
                    return tagged;
                }
            }
        }
        Instant snapshot = fixed;
        return snapshot != null ? snapshot : Instant.now();
    }

    /** 设定固定业务时刻。 */
    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    /** 恢复为系统时钟，并清除线程标签设置。 */
    public void reset() {
        this.fixed = null;
        this.threadTag.remove();
        synchronized (taggedTimes) {
            taggedTimes.clear();
        }
    }

    /** 为当前线程打标签（测试竞态用），传 null 清除。 */
    public void tagCurrentThread(String tag) {
        if (tag == null) {
            threadTag.remove();
        } else {
            threadTag.set(tag);
        }
    }

    /** 设定带标签线程读取的固定业务时刻（测试竞态用）。 */
    public void setTaggedTime(String tag, Instant instant) {
        synchronized (taggedTimes) {
            taggedTimes.put(tag, instant);
        }
    }
}
