package com.example.starter.api;

// 体征提交请求的包装：requestId 与体征内容。
public record VitalsAck(String requestId, boolean accepted) {
}
