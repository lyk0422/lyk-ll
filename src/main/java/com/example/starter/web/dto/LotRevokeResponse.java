package com.example.starter.web.dto;

/**
 * 注销结果：批次视图、同步作废的证书（无证书为空）以及是否本次实际注销（幂等重入为 false）。
 */
public record LotRevokeResponse(
        LotView lot,
        CertificateView revokedCertificate,
        boolean revokedNow
) {
}
