package com.example.starter.transition.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 品种及工艺窗口；克重单位 g/m²，车速 m/min，上料浓度 %，抗张指数 N·m/g。
 */
public class Grade {
    public Long id;
    /** 品种编码。 */
    public String gradeCode;
    /** 目标克重 g/m²。 */
    public BigDecimal basisWeight;
    /** 灰分等级（整数）。 */
    public Integer ashLevel;
    /** 车速窗口下限 m/min（边界含）。 */
    public BigDecimal speedMin;
    /** 车速窗口上限 m/min（边界含）。 */
    public BigDecimal speedMax;
    /** 上料浓度窗口下限 %（边界含）。 */
    public BigDecimal consistencyMin;
    /** 上料浓度窗口上限 %（边界含）。 */
    public BigDecimal consistencyMax;
    /** 抗张指数正品门槛 N·m/g（恰达即合格）。 */
    public BigDecimal tensileMin;
    public LocalDateTime createdAt;
}
