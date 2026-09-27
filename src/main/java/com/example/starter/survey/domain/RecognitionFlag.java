package com.example.starter.survey.domain;

/**
 * 某规则版本承认的船旗代码（合成代码）。
 *
 * @param id             主键
 * @param ruleVersionId  所属规则版本主键
 * @param flagCode       船旗代码
 */
public record RecognitionFlag(
        Long id,
        Long ruleVersionId,
        String flagCode
) {
}
