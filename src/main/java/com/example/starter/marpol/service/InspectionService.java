package com.example.starter.marpol.service;

import com.example.starter.marpol.domain.Conclusion;
import com.example.starter.marpol.domain.Defect;
import com.example.starter.marpol.domain.DefectStatus;
import com.example.starter.marpol.domain.EmissionRecord;
import com.example.starter.marpol.domain.Inspection;
import com.example.starter.marpol.domain.InspectionItem;
import com.example.starter.marpol.domain.InspectionStatus;
import com.example.starter.marpol.repo.EmissionRecordRepository;
import com.example.starter.marpol.repo.GeoReferenceRepository;
import com.example.starter.marpol.repo.InspectionRepository;
import com.example.starter.marpol.repo.ShipRepository;
import com.example.starter.marpol.support.ApiException;
import com.example.starter.marpol.support.TimeService;
import com.example.starter.marpol.web.DefectResponse;
import com.example.starter.marpol.web.InspectionCreateRequest;
import com.example.starter.marpol.web.InspectionDecisionRequest;
import com.example.starter.marpol.web.InspectionItemResponse;
import com.example.starter.marpol.web.InspectionResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 港口国核查：按船舶发起，逐条“确认/标注违规”，违规立即生成缺陷项（核查日 + 14 个自然日）。
 * 签发为终态：重复签发幂等成功，签发后结论不可修改，补充证据走复查。
 * 结论行记录结论时刻确认的记录版本，迟到修正不影响结论。
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final EmissionRecordRepository recordRepository;
    private final ShipRepository shipRepository;
    private final GeoReferenceRepository geoRepository;
    private final TimeService timeService;
    private final int rectificationDays;

    public InspectionService(InspectionRepository inspectionRepository,
                             EmissionRecordRepository recordRepository,
                             ShipRepository shipRepository,
                             GeoReferenceRepository geoRepository,
                             TimeService timeService,
                             @Value("${marpol.rectification-days:14}") int rectificationDays) {
        this.inspectionRepository = inspectionRepository;
        this.recordRepository = recordRepository;
        this.shipRepository = shipRepository;
        this.geoRepository = geoRepository;
        this.timeService = timeService;
        this.rectificationDays = rectificationDays;
    }

    @Transactional
    public InspectionResponse create(InspectionCreateRequest request) {
        if (shipRepository.findShip(request.shipId()).isEmpty()) {
            throw ApiException.notFound("船舶不存在: " + request.shipId());
        }
        if (!geoRepository.portExists(request.portCode())) {
            throw ApiException.notFound("港口不存在: " + request.portCode());
        }
        if (request.reviewOf() != null && !request.reviewOf().isBlank()) {
            Inspection original = inspectionRepository.find(request.reviewOf())
                    .orElseThrow(() -> ApiException.notFound(
                            "复查引用的原核查不存在: " + request.reviewOf()));
            if (original.getStatus() != InspectionStatus.ISSUED) {
                throw ApiException.conflict("仅可对已签发的核查发起复查: " + request.reviewOf());
            }
        }
        Inspection inspection = new Inspection();
        inspection.setInspectionNo(generateInspectionNo());
        inspection.setShipId(request.shipId());
        inspection.setPortCode(request.portCode());
        inspection.setInspectDate(request.inspectDate() != null
                ? request.inspectDate()
                : timeService.now().atZone(java.time.ZoneOffset.UTC).toLocalDate());
        inspection.setStatus(InspectionStatus.OPEN);
        inspection.setReviewOf((request.reviewOf() == null || request.reviewOf().isBlank())
                ? null : request.reviewOf());
        inspection.setCreatedAt(timeService.now());
        inspectionRepository.insertInspection(inspection);
        return toResponse(inspection);
    }

    /**
     * 逐条结论。签发后一律拒绝；结论时刻固定记录版本，后续修正不影响结论。
     */
    @Transactional
    public InspectionItemResponse decide(String inspectionNo, InspectionDecisionRequest request) {
        Conclusion conclusion = parseConclusion(request.conclusion());
        Inspection inspection = inspectionRepository.find(inspectionNo)
                .orElseThrow(() -> ApiException.notFound("核查不存在: " + inspectionNo));
        if (inspection.getStatus() == InspectionStatus.ISSUED) {
            throw ApiException.conflict("核查 " + inspectionNo + " 已签发（终态），结论不可修改");
        }

        var existing = inspectionRepository.findItem(inspectionNo, request.recordNo());
        if (existing.isPresent()) {
            InspectionItem item = existing.get();
            if (item.getConclusion() == conclusion) {
                return toItemResponse(item,
                        inspectionRepository.findDefectByItem(item.getId()).orElse(null));
            }
            throw ApiException.conflict("记录 " + request.recordNo() + " 在核查 " + inspectionNo
                    + " 中已有结论 " + item.getConclusion() + "，不可改为 " + conclusion);
        }

        // 锁定结论时刻的 ACTIVE 版本行，与并发修正互斥：若修正先提交则当前读到新版本；
        // 若结论先落定，则随后提交的修正成为不影响该结论的迟到修正。
        // H2 的 FOR UPDATE 在等待对方行锁后只复验旧匹配行，可能漏掉对方新插入的 ACTIVE 行，
        // 因此锁定为空时改用普通当前读取最新版本，再对该行加锁确认。
        EmissionRecord active = recordRepository.findActiveVersionForUpdate(request.recordNo())
                .orElse(null);
        if (active == null) {
            EmissionRecord latest = recordRepository.findActiveVersion(request.recordNo())
                    .orElse(null);
            if (latest != null) {
                active = recordRepository
                        .findVersionForUpdate(request.recordNo(), latest.getVersion())
                        .filter(r -> r.getStatus()
                                == com.example.starter.marpol.domain.RecordStatus.ACTIVE)
                        .orElse(null);
            }
        }
        if (active == null) {
            throw ApiException.notFound("记录不存在或无有效版本: " + request.recordNo());
        }

        InspectionItem item = new InspectionItem();
        item.setInspectionNo(inspectionNo);
        item.setRecordNo(active.getRecordNo());
        item.setRecordVersion(active.getVersion());
        item.setConclusion(conclusion);
        item.setEvidence(request.evidence());
        item.setDecidedAt(timeService.now());
        try {
            inspectionRepository.insertItem(item);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("记录 " + request.recordNo() + " 在核查 " + inspectionNo
                    + " 中已被并发标注");
        }

        Defect defect = null;
        if (conclusion == Conclusion.VIOLATION) {
            defect = new Defect();
            defect.setInspectionNo(inspectionNo);
            defect.setItemId(item.getId());
            defect.setRecordNo(active.getRecordNo());
            defect.setRecordVersion(active.getVersion());
            defect.setDeadline(inspection.getInspectDate().plusDays(rectificationDays));
            defect.setStatus(DefectStatus.OPEN);
            defect.setCreatedAt(timeService.now());
            inspectionRepository.insertDefect(defect);
        }
        return toItemResponse(item, defect);
    }

    /**
     * 签发核查（终态）。重复签发返回幂等成功。
     */
    @Transactional
    public InspectionResponse issue(String inspectionNo) {
        Inspection inspection = inspectionRepository.find(inspectionNo)
                .orElseThrow(() -> ApiException.notFound("核查不存在: " + inspectionNo));
        if (inspection.getStatus() == InspectionStatus.ISSUED) {
            return toResponse(inspection);
        }
        int updated = inspectionRepository.markIssued(inspectionNo, timeService.now());
        if (updated == 0) {
            // 并发签发：以先签发者为准，返回幂等成功
            inspection = inspectionRepository.find(inspectionNo).orElseThrow();
        } else {
            inspection = inspectionRepository.find(inspectionNo).orElseThrow();
        }
        return toResponse(inspection);
    }

    @Transactional(readOnly = true)
    public InspectionResponse get(String inspectionNo) {
        return toResponse(inspectionRepository.find(inspectionNo)
                .orElseThrow(() -> ApiException.notFound("核查不存在: " + inspectionNo)));
    }

    @Transactional(readOnly = true)
    public List<InspectionItemResponse> listItems(String inspectionNo) {
        requireInspection(inspectionNo);
        return inspectionRepository.findItems(inspectionNo).stream()
                .map(item -> toItemResponse(item,
                        inspectionRepository.findDefectByItem(item.getId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DefectResponse> listDefects(String inspectionNo) {
        requireInspection(inspectionNo);
        refreshOverdue();
        return inspectionRepository.findDefects(inspectionNo).stream()
                .map(this::toDefectResponse)
                .toList();
    }

    /**
     * 关闭缺陷整改：deadline 当天（恰第 14 个自然日）关闭有效，超期拒绝并标 OVERDUE。
     */
    @Transactional
    public DefectResponse closeDefect(long defectId) {
        Defect defect = inspectionRepository.findDefect(defectId)
                .orElseThrow(() -> ApiException.notFound("缺陷不存在: " + defectId));
        LocalDate today = timeService.now().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if (defect.getStatus() == DefectStatus.CLOSED) {
            return toDefectResponse(defect);
        }
        if (defect.getDeadline().isBefore(today)) {
            inspectionRepository.markOverdueBefore(today);
            throw ApiException.unprocessable("缺陷 " + defectId + " 已于 "
                    + defect.getDeadline() + " 到期，当前 " + today + "，不可按期关闭");
        }
        int updated = inspectionRepository.closeDefectOnTime(defectId, today, timeService.now());
        if (updated == 0) {
            throw ApiException.conflict("缺陷 " + defectId + " 已关闭或状态已变化");
        }
        return toDefectResponse(inspectionRepository.findDefect(defectId).orElseThrow());
    }

    private void refreshOverdue() {
        LocalDate today = timeService.now().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        inspectionRepository.markOverdueBefore(today);
    }

    private void requireInspection(String inspectionNo) {
        if (inspectionRepository.find(inspectionNo).isEmpty()) {
            throw ApiException.notFound("核查不存在: " + inspectionNo);
        }
    }

    private Conclusion parseConclusion(String raw) {
        return switch (raw == null ? "" : raw) {
            case "CONFIRM", "CONFIRMED" -> Conclusion.CONFIRMED;
            case "VIOLATION" -> Conclusion.VIOLATION;
            default -> throw ApiException.badRequest(
                    "conclusion 仅支持 CONFIRM 或 VIOLATION: " + raw);
        };
    }

    private String generateInspectionNo() {
        return "PSC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private InspectionResponse toResponse(Inspection i) {
        return new InspectionResponse(i.getInspectionNo(), i.getShipId(), i.getPortCode(),
                i.getInspectDate(), i.getStatus().name(), i.getReviewOf(), i.getIssuedAt(),
                i.getCreatedAt());
    }

    private InspectionItemResponse toItemResponse(InspectionItem item, Defect defect) {
        return new InspectionItemResponse(item.getId(), item.getInspectionNo(),
                item.getRecordNo(), item.getRecordVersion(), item.getConclusion().name(),
                item.getEvidence(), item.getDecidedAt(),
                defect == null ? null : defect.getId(),
                defect == null ? null : defect.getStatus().name(),
                defect == null ? null : defect.getDeadline());
    }

    private DefectResponse toDefectResponse(Defect d) {
        return new DefectResponse(d.getId(), d.getInspectionNo(), d.getRecordNo(),
                d.getRecordVersion(), d.getDeadline(), d.getStatus().name(), d.getClosedAt(),
                d.getCreatedAt());
    }
}
