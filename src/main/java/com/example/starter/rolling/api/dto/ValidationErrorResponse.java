package com.example.starter.rolling.api.dto;

import java.util.List;

/**
 * 违例校验错误体：整单拒绝时返回全部违例明细。
 */
public record ValidationErrorResponse(String code, String message, List<String> violations) {
}
