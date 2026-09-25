package com.example.starter.quarantine.service;

import com.example.starter.quarantine.api.dto.CaseView;
import com.example.starter.quarantine.api.dto.CertificateView;
import com.example.starter.quarantine.domain.CertificatePo;
import com.example.starter.quarantine.domain.QuarantineRequestPo;

/**
 * PO → 对外视图装配。
 */
public final class CaseViews {

    private CaseViews() {
    }

    public static CertificateView certificate(CertificatePo cert) {
        if (cert == null) {
            return null;
        }
        return new CertificateView(
                cert.getCertificateNo(), cert.getRequestId(), cert.getFarmId(),
                cert.getAnimalType().name(), cert.getEarTagRange(), cert.getQuantity(),
                cert.getIssuedAt(), cert.getExpiresAt(), cert.getStatus(),
                cert.isQuarantineFlag());
    }

    public static CaseView of(QuarantineRequestPo request, CertificatePo cert,
                              int landedQuantity, boolean replay) {
        boolean quarantineFlag = cert != null && cert.isQuarantineFlag();
        return new CaseView(
                request.getRequestId(), request.getOriginFarm(), request.getDestination(),
                request.getAnimalType().name(), request.getEarTagRange(), request.getQuantity(),
                request.getStatus(), certificate(cert), landedQuantity, quarantineFlag,
                request.getRejectReason(), replay);
    }
}
