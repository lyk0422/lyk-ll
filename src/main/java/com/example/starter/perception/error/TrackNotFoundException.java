package com.example.starter.perception.error;

/**
 * 轨迹不存在或指定时刻尚无该轨迹，映射 HTTP 404。
 */
public class TrackNotFoundException extends RuntimeException {

    public TrackNotFoundException(String trackId) {
super("track not found: " + trackId);
    }
}
