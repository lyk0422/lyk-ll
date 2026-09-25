package com.example.starter.flood.support;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 业务编号生成：前缀 + 时间戳 + 随机位；依赖数据库唯一索引兜底，碰撞时由调用方重试。
 */
@Component
public class BusinessNoGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Clock clock;

    public BusinessNoGenerator(Clock floodClock) {
        this.clock = floodClock;
    }

    public String next(String prefix) {
        LocalDateTime now = LocalDateTime.now(clock);
        int random = ThreadLocalRandom.current().nextInt(1000, 10000);
        return prefix + now.format(FMT) + random;
    }
}
