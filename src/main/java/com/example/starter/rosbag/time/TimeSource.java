package com.example.starter.rosbag.time;

/**
 * 可替换的时间源，返回纳秒级绝对时间（UTC 纪元）；测试可提供可控实现。
 */
public interface TimeSource {

    long nowEpochNs();
}
