package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 签发归档请求。同批号重复归档幂等成功。
 *
 * @param requestId 幂等键
 */
public record IssueRequest(@NotBlank String requestId) {
}
