package com.example.starter.survey.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 检验类型字典行。
 */
public record SurveyTypeRow(String code, String name, boolean extendable) {
}
