package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 水质限值与联动时限常量。恰等限值判定为正常（严格不等式越限）。
 */
public final class Thresholds {

    /** 溶解氧下限，单位 mg/L；低于该值越限，恰等正常 */
    public static final BigDecimal DO_MIN = new BigDecimal("5.0");
    /** 氨氮上限，单位 mg/L；高于该值越限，恰等正常 */
    public static final BigDecimal NH3N_MAX = new BigDecimal("0.50");
    /** 水温下限，单位 摄氏度；低于该值越限，恰等正常 */
    public static final BigDecimal TEMP_MIN = new BigDecimal("18.0");
    /** 水温上限，单位 摄氏度；高于该值越限，恰等正常 */
    public static final BigDecimal TEMP_MAX = new BigDecimal("28.0");

    /** 连续越限采样点数达到该值即升级为紧急状态（恰 3 点触发） */
    public static final int EMERGENCY_CONSECUTIVE = 3;
    /** 处置回执确认时限，恰满 5 分钟未确认即升级 */
    public static final Duration RECEIPT_TIMEOUT = Duration.ofMinutes(5);
    /** 传感器失联判定时长，恰满 10 分钟标记数据缺失 */
    public static final Duration SENSOR_LOST_AFTER = Duration.ofMinutes(10);

    /** 越限参数标识：溶解氧 */
    public static final String PARAM_DO = "DO";
    /** 越限参数标识：氨氮 */
    public static final String PARAM_NH3N = "NH3N";
    /** 越限参数标识：温度 */
    public static final String PARAM_TEMP = "TEMP";

    private Thresholds() {
    }

    /**
     * 判定越限参数列表；恰等限值不越限。返回空列表表示全部正常。
     */
    public static List<String> violatedParams(BigDecimal dissolvedOxygen, BigDecimal ammoniaNitrogen,
                                              BigDecimal temperature) {
        List<String> violated = new ArrayList<>();
        if (dissolvedOxygen.compareTo(DO_MIN) < 0) {
            violated.add(PARAM_DO);
        }
        if (ammoniaNitrogen.compareTo(NH3N_MAX) > 0) {
            violated.add(PARAM_NH3N);
        }
        if (temperature.compareTo(TEMP_MIN) < 0 || temperature.compareTo(TEMP_MAX) > 0) {
            violated.add(PARAM_TEMP);
        }
        return violated;
    }

    /**
     * 复测合格判定：全部参数不越限（恰等限值视为合格）。
     */
    public static boolean isQualified(BigDecimal dissolvedOxygen, BigDecimal ammoniaNitrogen,
                                      BigDecimal temperature) {
        return violatedParams(dissolvedOxygen, ammoniaNitrogen, temperature).isEmpty();
    }

    /**
     * 依据越限参数选择首发出置指令类型：溶解氧越限→增氧，氨氮越限→换水，温度越限→停展隔离。
     */
    public static CommandType commandTypeFor(List<String> violatedParams) {
        if (violatedParams.contains(PARAM_DO)) {
            return CommandType.AERATION;
        }
        if (violatedParams.contains(PARAM_NH3N)) {
            return CommandType.WATER_CHANGE;
        }
        return CommandType.CLOSURE_ISOLATION;
    }
}
