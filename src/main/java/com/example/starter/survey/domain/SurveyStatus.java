package com.example.starter.survey.domain;

/**
 * 检验流转状态：申请受理 -> 登轮完成 -> 已发证（终态）。
 */
public enum SurveyStatus {
    /** 已受理申请，检验进行中。 */
    APPLIED,
    /** 已完成登轮检验，缺陷已登记。 */
    BOARDED,
    /** 已签发/换发证书，终态。 */
    CERTIFIED
}
