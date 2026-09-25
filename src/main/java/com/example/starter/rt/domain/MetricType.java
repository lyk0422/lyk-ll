package com.example.starter.rt.domain;

/**
 * 危及器官剂量体积指标类型。
 */
public enum MetricType {

    /** 最大剂量，限值单位 cGy，申报值单位 cGy。 */
    DMAX_CGY,
    /** 不低于参照剂量（metric_ref，单位 cGy）的体积占比，限值与申报值单位均为 %。 */
    V_DOSE_PCT
}
