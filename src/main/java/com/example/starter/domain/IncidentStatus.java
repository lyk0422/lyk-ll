package com.example.starter.domain;

/**
 * 途中异常处置状态：OPEN 阻断中（续行须重新试验与双人确认）；RESUMED 已续行（仅结束本次阻断，证据保留）。
 */
public enum IncidentStatus {
    OPEN,
    RESUMED
}
