package com.example.starter.rosbag;

import com.example.starter.rosbag.time.TimeSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控时钟：默认固定在某纳秒时刻，可在测试中调整。
 */
@TestConfiguration
public class ControllableTimeSource implements TimeSource {

    private volatile long current = 1_700_000_000_000_000_000L;

    public void set(long epochNs) {
        this.current = epochNs;
    }

    @Override
    public long nowEpochNs() {
        return current;
    }

    @Bean
    @Primary
    public TimeSource controllableTimeSource() {
        return this;
    }
}
