package com.example.starter.transition.model;

import java.math.BigDecimal;

/**
 * 品种工艺主数据（合成数据）。
 */
public class Grade {

    private String gradeCode;
    private String gradeName;
    /** 克重，单位 g/m2。 */
    private BigDecimal basisWeight;
    /** 灰分等级，整数级。 */
    private int ashLevel;
    /** 工艺窗口-车速下限，单位 m/min（含边界）。 */
    private BigDecimal speedMin;
    /** 工艺窗口-车速上限，单位 m/min（含边界）。 */
    private BigDecimal speedMax;
    /** 工艺窗口-上料浓度下限，单位 %（含边界）。 */
    private BigDecimal consistencyMin;
    /** 工艺窗口-上料浓度上限，单位 %（含边界）。 */
    private BigDecimal consistencyMax;

    public String getGradeCode() {
        return gradeCode;
    }

    public void setGradeCode(String gradeCode) {
        this.gradeCode = gradeCode;
    }

    public String getGradeName() {
        return gradeName;
    }

    public void setGradeName(String gradeName) {
        this.gradeName = gradeName;
    }

    public BigDecimal getBasisWeight() {
        return basisWeight;
    }

    public void setBasisWeight(BigDecimal basisWeight) {
        this.basisWeight = basisWeight;
    }

    public int getAshLevel() {
        return ashLevel;
    }

    public void setAshLevel(int ashLevel) {
        this.ashLevel = ashLevel;
    }

    public BigDecimal getSpeedMin() {
        return speedMin;
    }

    public void setSpeedMin(BigDecimal speedMin) {
        this.speedMin = speedMin;
    }

    public BigDecimal getSpeedMax() {
        return speedMax;
    }

    public void setSpeedMax(BigDecimal speedMax) {
        this.speedMax = speedMax;
    }

    public BigDecimal getConsistencyMin() {
        return consistencyMin;
    }

    public void setConsistencyMin(BigDecimal consistencyMin) {
        this.consistencyMin = consistencyMin;
    }

    public BigDecimal getConsistencyMax() {
        return consistencyMax;
    }

    public void setConsistencyMax(BigDecimal consistencyMax) {
        this.consistencyMax = consistencyMax;
    }
}
