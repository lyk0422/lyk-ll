package com.example.starter.epidemic.domain;

/**
 * 诊断类型：疑似/确诊。疑似转确诊属于病种变更类订正，须重起审核。
 */
public enum DiagnosisType {
    SUSPECTED,
    CONFIRMED
}
