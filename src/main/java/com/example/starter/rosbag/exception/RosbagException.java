package com.example.starter.rosbag.exception;

/**
 * rosbag 业务异常基类，映射为 HTTP 409。
 */
public class RosbagException extends RuntimeException {

    public RosbagException(String message) {
        super(message);
    }
}
