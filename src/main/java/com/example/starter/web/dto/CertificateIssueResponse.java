package com.example.starter.web.dto;

/**
 * 发证结果：证书与发证后的批次视图。
 */
public record CertificateIssueResponse(
        CertificateView certificate,
        LotView lot
) {
}
