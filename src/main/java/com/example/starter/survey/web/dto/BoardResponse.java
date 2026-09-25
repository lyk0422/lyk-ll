package com.example.starter.survey.web.dto;

import java.util.List;

/**
 * 登轮检验结果视图。
 *
 * @param survey       检验记录
 * @param defects      本次登记的缺陷链
 * @param acceptedAsLate 签发终态后迟到提交时为 true：仅登记迟到事件，检验状态不变
 */
public record BoardResponse(
        SurveyResponse survey,
        List<DefectResponse> defects,
        boolean acceptedAsLate
) {
}
