package com.example.starter.web.dto;

import java.util.List;

/**
 * 批次详情：批次、全部田间/室内检验记录（含失效首检）、证书与留痕。
 */
public record LotDetailView(
        LotView lot,
        List<InspectionView> fieldInspections,
        List<InspectionView> labInspections,
        CertificateView certificate,
        List<AuditView> audits
) {
}
