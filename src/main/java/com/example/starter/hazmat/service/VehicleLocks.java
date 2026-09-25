package com.example.starter.hazmat.service;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * 车辆维度分段锁：单 JVM 内串行化同一车辆的申请/发运等关键检查，
 * 与数据库原子迁移共同保证“同一车辆同时刻只允许一个执行中运单”。
 */
@Component
public class VehicleLocks {

    private final List<ReentrantLock> stripes =
            IntStream.range(0, 64).mapToObj(i -> new ReentrantLock()).toList();

    public <T> T execute(String vehicleCode, Supplier<T> action) {
        ReentrantLock lock = stripes.get(Math.floorMod(vehicleCode.hashCode(), stripes.size()));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
