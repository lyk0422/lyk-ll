package com.example.starter.ride;

/**
 * 幂等请求的业务类型。
 */
public enum RequestType {

    DAILY_CHECK,
    TRIAL_RUN,
    OPEN,
    SAFETY_FAULT,
    READING,
    RECHECK,
    ARCHIVE
}
