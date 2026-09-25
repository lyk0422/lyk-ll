package com.example.starter.lc.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 银行拒付通知请求。必须一次性列明全部不符点；以列表规范化排序作为指纹一部分。
 */
public record NoticeRequest(
        @NotBlank String requestId,
        @NotBlank String presentationNo,
        @NotEmpty @Valid List<DiscrepancyInput> discrepancies) {
}
