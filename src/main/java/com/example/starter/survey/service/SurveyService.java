package com.example.starter.survey.service;

import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.domain.CertificateRow;
import com.example.starter.survey.domain.DefectRow;
import com.example.starter.survey.domain.ExtensionRow;
import com.example.starter.survey.domain.ShipRow;
import com.example.starter.survey.domain.SurveyEventRow;
import com.example.starter.survey.domain.SurveyRow;
import com.example.starter.survey.domain.SurveyTypeRow;
import com.example.starter.survey.model.SurveyEnums.LedgerState;
import com.example.starter.survey.model.SurveyEnums.Operation;
import com.example.starter.survey.model.SurveyEnums.SurveyType;
import com.example.starter.survey.repo.CertificateRepo;
import com.example.starter.survey.repo.ExtensionRepo;
import com.example.starter.survey.repo.LedgerRepo;
import com.example.starter.survey.repo.ShipTypeRepo;
import com.example.starter.survey.repo.SurveyRepo;
import com.example.starter.survey.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 检验服务门面：负责实体解析、幂等指纹与执行编排；业务原子性由 {@link SurveyTxService} 保证。
 */
@Service
public class SurveyService {

    private final SurveyTxService tx;
    private final Idempotency idempotency;
    private final ShipTypeRepo shipTypeRepo;
    private final SurveyRepo surveyRepo;
    private final CertificateRepo certificateRepo;
    private final ExtensionRepo extensionRepo;
    private final LedgerRepo ledgerRepo;
    private final ViewMapper viewMapper;

    public SurveyService(SurveyTxService tx, Idempotency idempotency,
                         ShipTypeRepo shipTypeRepo, SurveyRepo surveyRepo,
                         CertificateRepo certificateRepo, ExtensionRepo extensionRepo,
                         LedgerRepo ledgerRepo, ViewMapper viewMapper) {
        this.tx = tx;
        this.idempotency = idempotency;
        this.shipTypeRepo = shipTypeRepo;
        this.surveyRepo = surveyRepo;
        this.certificateRepo = certificateRepo;
        this.extensionRepo = extensionRepo;
        this.ledgerRepo = ledgerRepo;
        this.viewMapper = viewMapper;
    }

    public Idempotency.Result applySurvey(Dtos.ApplySurveyRequest req) {
        ShipRow ship = requireShip(req.imo());
        SurveyTypeRow type = requireType(req.typeCode());
        String fp = idempotency.fingerprint(Operation.APPLY_SURVEY, req.requestId(),
                ship.id(), type.code());
        return idempotency.execute(Operation.APPLY_SURVEY, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(tx.applySurvey(req.requestId(), ship, type)));
    }

    public Idempotency.Result boardSurvey(Dtos.BoardSurveyRequest req) {
        SurveyRow survey = requireSurvey(req.surveyId());
        ShipRow ship = requireShipById(survey.shipId());
        List<Dtos.DefectInput> defects = req.defects() == null ? List.of() : req.defects();
        String defectSig = defects.stream()
                .map(d -> d.severity() + ":" + d.description())
                .sorted()
                .reduce((a, b) -> a + ";" + b).orElse("");
        String fp = idempotency.fingerprint(Operation.BOARD_SURVEY, req.requestId(),
                survey.id(), req.boardDate(), defectSig);
        return idempotency.execute(Operation.BOARD_SURVEY, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(tx.boardSurvey(survey, ship, req.boardDate(), defects)));
    }

    public Idempotency.Result rectifyDefect(Dtos.RectifyDefectRequest req) {
        DefectRow defect = requireDefect(req.defectId());
        SurveyRow survey = requireSurvey(defect.surveyId());
        String fp = idempotency.fingerprint(Operation.RECTIFY_DEFECT, req.requestId(),
                defect.id(), req.rectifiedDate());
        return idempotency.execute(Operation.RECTIFY_DEFECT, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(
                        tx.rectifyDefect(defect, survey, req.rectifiedDate())));
    }

    public Idempotency.Result recheckDefect(Dtos.RecheckDefectRequest req) {
        DefectRow defect = requireDefect(req.defectId());
        SurveyRow survey = requireSurvey(defect.surveyId());
        ShipRow ship = requireShipById(survey.shipId());
        String fp = idempotency.fingerprint(Operation.RECHECK_DEFECT, req.requestId(),
                defect.id(), req.submitDate());
        return idempotency.execute(Operation.RECHECK_DEFECT, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(
                        tx.recheckDefect(defect, survey, ship, req.submitDate())));
    }

    public Idempotency.Result issueCertificate(Dtos.IssueCertificateRequest req) {
        SurveyRow survey = requireSurvey(req.surveyId());
        ShipRow ship = requireShipById(survey.shipId());
        String fp = idempotency.fingerprint(Operation.ISSUE_CERTIFICATE, req.requestId(),
                survey.id(), req.issueDate());
        return idempotency.execute(Operation.ISSUE_CERTIFICATE, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(tx.issueCertificate(survey, ship, req.issueDate())));
    }

    public Idempotency.Result applyExtension(Dtos.ApplyExtensionRequest req) {
        ShipRow ship = requireShip(req.imo());
        SurveyTypeRow type = requireType(req.typeCode());
        String fp = idempotency.fingerprint(Operation.APPLY_EXTENSION, req.requestId(),
                ship.id(), type.code(), req.months());
        return idempotency.execute(Operation.APPLY_EXTENSION, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(
                        tx.applyExtension(req.requestId(), ship, type, req.months())));
    }

    public Idempotency.Result approveExtension(Dtos.ApproveExtensionRequest req) {
        ExtensionRow extension = requireExtension(req.extensionId());
        ShipRow ship = requireShipById(extension.shipId());
        String fp = idempotency.fingerprint(Operation.APPROVE_EXTENSION, req.requestId(),
                extension.id());
        return idempotency.execute(Operation.APPROVE_EXTENSION, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(
                        tx.approveExtension(extension, ship, req.requestId())));
    }

    public Idempotency.Result lateRecord(Dtos.LateRecordRequest req) {
        SurveyRow survey = requireSurvey(req.surveyId());
        String fp = idempotency.fingerprint(Operation.LATE_RECORD, req.requestId(),
                survey.id(), req.recordDate(), req.detail() == null ? "" : req.detail());
        return idempotency.execute(Operation.LATE_RECORD, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(
                        tx.lateRecord(survey, req.recordDate(), req.detail())));
    }

    /** 复算：窗口台账、证书版本链与缺陷链一致性校验（只读，按 requestId 幂等）。 */
    public Idempotency.Result recompute(Dtos.RecomputeRequest req) {
        String fp = idempotency.fingerprint(Operation.RECOMPUTE, req.requestId(), req.asOf());
        return idempotency.execute(Operation.RECOMPUTE, req.requestId(), fp,
                () -> Idempotency.Outcome.ok(doRecompute(req.asOf())));
    }

    private Dtos.RecomputeResult doRecompute(LocalDate asOf) {
        int cycles = 0;
        int chains = 0;
        StringBuilder digest = new StringBuilder();
        for (ShipRow ship : shipTypeRepo.findAllShips()) {
            for (SurveyType type : SurveyType.values()) {
                List<CertificateRow> certs = certificateRepo.findByShipType(ship.id(), type.name());
                long validCount = certs.stream().filter(c -> Boolean.TRUE.equals(c.validFlag())).count();
                if (validCount > 1) {
                    throw ApiException.conflict("复算发现 " + ship.imo() + " " + type
                            + " 存在多个有效证书");
                }
                if (!certs.isEmpty()) {
                    chains++;
                    digest.append(ship.imo()).append(':').append(type).append("=v")
                            .append(certs.get(certs.size() - 1).version())
                            .append(validCount == 1 ? ";VALID;" : ";NONE;");
                }
            }
            for (var ledger : ledgerRepo.findByShip(ship.id())) {
                cycles++;
                LocalDate effectiveEnd = ledger.extendedEnd() == null
                        ? ledger.windowEnd() : ledger.extendedEnd();
                if (ledger.state() == LedgerState.IN_WINDOW && ledger.boardedAt() != null) {
                    boolean inWindow = !ledger.boardedAt().isBefore(ledger.windowStart())
                            && !ledger.boardedAt().isAfter(effectiveEnd);
                    if (!inWindow) {
                        throw ApiException.conflict("复算发现台账窗口不一致：ship=" + ship.id()
                                + " cycle=" + ledger.cycleYear());
                    }
                }
            }
        }
        // 缺陷链复算：复查截止恒为发现日 +30；状态迁移须合法。
        for (Long surveyId : surveyRepo.findAllSurveyIds()) {
            for (var d : surveyRepo.findDefectsBySurvey(surveyId)) {
                LocalDate expectedDue = d.severity() == com.example.starter.survey.model.SurveyEnums.Severity.GENERAL
                        ? SurveyDates.recheckDue(d.foundDate()) : null;
                if (!java.util.Objects.equals(expectedDue, d.dueDate())) {
                    throw ApiException.conflict("复算发现缺陷#" + d.id() + " 复查截止日不一致");
                }
                switch (d.status()) {
                    case ACCEPTED -> {
                        if (d.rectifiedDate() == null) {
                            throw ApiException.conflict("复算发现缺陷#" + d.id() + " 缺少整改日");
                        }
                    }
                    case RECTIFIED -> {
                        if (d.rectifiedDate() == null || d.acceptedDate() != null) {
                            throw ApiException.conflict("复算发现缺陷#" + d.id() + " 整改链不一致");
                        }
                    }
                    case EXPIRED -> {
                        if (d.severity() != com.example.starter.survey.model.SurveyEnums.Severity.GENERAL) {
                            throw ApiException.conflict("复算发现重大缺陷#" + d.id() + " 不应为逾期态");
                        }
                    }
                    default -> {
                    }
                }
                digest.append("D").append(d.id()).append('=')
                        .append(d.severity()).append(':').append(d.status()).append(';');
            }
        }
        return new Dtos.RecomputeResult(asOf, cycles, chains, digest.toString());
    }

    /** 查询：船舶列表。 */
    @Transactional(readOnly = true)
    public List<Dtos.ShipView> listShips() {
        return shipTypeRepo.findAllShips().stream().map(viewMapper::ship).toList();
    }

    @Transactional(readOnly = true)
    public Dtos.SurveyView getSurvey(long surveyId) {
        SurveyRow survey = requireSurvey(surveyId);
        return viewMapper.survey(survey, requireShipById(survey.shipId()).imo());
    }

    @Transactional(readOnly = true)
    public List<Dtos.DefectView> listDefects(long surveyId) {
        requireSurvey(surveyId);
        return surveyRepo.findDefectsBySurvey(surveyId).stream()
                .map(viewMapper::defect).toList();
    }

    @Transactional(readOnly = true)
    public List<SurveyEventRow> listEvents(long surveyId) {
        requireSurvey(surveyId);
        return surveyRepo.findEvents(surveyId);
    }

    @Transactional(readOnly = true)
    public Dtos.CertificateView getValidCertificate(String imo, String typeCode) {
        ShipRow ship = requireShip(imo);
        requireType(typeCode);
        CertificateRow cert = certificateRepo.findValidCertificate(ship.id(), typeCode);
        if (cert == null) {
            return null;
        }
        return viewMapper.certificate(cert, ship.imo());
    }

    @Transactional(readOnly = true)
    public List<Dtos.CertificateView> listCertificateChain(String imo, String typeCode) {
        ShipRow ship = requireShip(imo);
        requireType(typeCode);
        return certificateRepo.findByShipType(ship.id(), typeCode).stream()
                .map(c -> viewMapper.certificate(c, ship.imo())).toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.LedgerView> listLedger(String imo) {
        ShipRow ship = requireShip(imo);
        return ledgerRepo.findByShip(ship.id()).stream().map(viewMapper::ledger).toList();
    }

    @Transactional(readOnly = true)
    public Dtos.ExtensionView getExtension(long extensionId) {
        ExtensionRow row = requireExtension(extensionId);
        return viewMapper.extension(row, requireShipById(row.shipId()).imo());
    }

    private ShipRow requireShip(String imo) {
        ShipRow ship = shipTypeRepo.findShipByImo(imo);
        if (ship == null) {
            throw ApiException.notFound("船舶不存在：" + imo);
        }
        return ship;
    }

    private ShipRow requireShipById(long shipId) {
        ShipRow ship = shipTypeRepo.findShipById(shipId);
        if (ship == null) {
            throw ApiException.notFound("船舶不存在：id=" + shipId);
        }
        return ship;
    }

    private SurveyTypeRow requireType(String code) {
        SurveyTypeRow type = shipTypeRepo.findType(code);
        if (type == null) {
            throw ApiException.badRequest("未知检验类型：" + code
                    + "（ANNUAL/INTERMEDIATE/SPECIAL）");
        }
        return type;
    }

    private SurveyRow requireSurvey(long id) {
        SurveyRow survey = surveyRepo.findSurvey(id);
        if (survey == null) {
            throw ApiException.notFound("检验单不存在：id=" + id);
        }
        return survey;
    }

    private DefectRow requireDefect(long id) {
        DefectRow defect = surveyRepo.findDefect(id);
        if (defect == null) {
            throw ApiException.notFound("缺陷不存在：id=" + id);
        }
        return defect;
    }

    private ExtensionRow requireExtension(long id) {
        ExtensionRow extension = extensionRepo.findById(id);
        if (extension == null) {
            throw ApiException.notFound("展期申请不存在：id=" + id);
        }
        return extension;
    }
}
