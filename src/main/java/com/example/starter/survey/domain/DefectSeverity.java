package com.example.starter.survey.domain;

/**
 * 缺陷严重程度：重大缺陷未整改不得发证；一般缺陷限期 30 日复查。
 */
public enum DefectSeverity {
    /** 重大缺陷：发证前必须整改完成。 */
    MAJOR,
    /** 一般缺陷：限发现日起 30 日内复查，恰第 30 日受理。 */
    MINOR
}
