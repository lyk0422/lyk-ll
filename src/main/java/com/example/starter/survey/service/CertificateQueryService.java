package com.example.starter.survey.service;

import com.example.starter.survey.data.CertificateRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.domain.Certificate;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.CertificateResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/** 证书版本链查询：版本链可复算核对。 */
@Service
public class CertificateQueryService {

    private final CertificateRepository certificateRepository;
    private final ShipRepository shipRepository;

    public CertificateQueryService(CertificateRepository certificateRepository,
                                   ShipRepository shipRepository) {
        this.certificateRepository = certificateRepository;
        this.shipRepository = shipRepository;
    }

    /** 列出某船某检验类型的全部证书版本（按版本号）。 */
    public List<CertificateResponse> versions(String shipImo, SurveyType type) {
        Ship ship = shipRepository.findByImo(shipImo)
                .orElseThrow(() -> new NotFoundException("SHIP_NOT_FOUND",
                        "船舶不存在: " + shipImo));
        return certificateRepository.findVersions(ship.id(), type).stream()
                .map(c -> CertificateResponse.from(c, ship.imo()))
                .toList();
    }

    /** 当前有效证书（无则 404）。 */
    public CertificateResponse valid(String shipImo, SurveyType type) {
        Ship ship = shipRepository.findByImo(shipImo)
                .orElseThrow(() -> new NotFoundException("SHIP_NOT_FOUND",
                        "船舶不存在: " + shipImo));
        Certificate cert = certificateRepository.findValid(ship.id(), type)
                .orElseThrow(() -> new NotFoundException("CERTIFICATE_NOT_FOUND",
                        "当前无有效证书: " + shipImo + " / " + type));
        return CertificateResponse.from(cert, ship.imo());
    }
}
