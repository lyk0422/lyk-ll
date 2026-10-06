package com.example.starter.domain;

/**
 * 干扰事件状态：OPEN 待初查；CONFIRMED 初查确认干扰、限功率中；REJECTED 初查排除干扰；CLOSED 结案。
 */
public enum EventStatus {
    OPEN,
    CONFIRMED,
    REJECTED,
    CLOSED
}
