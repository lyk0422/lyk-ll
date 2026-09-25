package com.example.starter.api;

// 体征数据登记结果。
// late=TRUE 表示结案后迟到数据，仅登记迟到事件；chamberActivated=TRUE 表示本次触发加压舱预案。
public record VitalsResponse(
        String operationNo,
        String requestId,
        String status,
        boolean late,
        boolean chamberActivated) {
}
