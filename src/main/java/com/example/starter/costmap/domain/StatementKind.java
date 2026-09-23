package com.example.starter.costmap.domain;

/**
 * 障碍物声明类型。
 */
public enum StatementKind {

    /** 占据：获胜时该格动态代价为 254（致命障碍）。 */
    OCCUPY,

    /** 清除：获胜时该格动态代价为 0（该来源观测到此处无动态障碍）。 */
    CLEAR
}
