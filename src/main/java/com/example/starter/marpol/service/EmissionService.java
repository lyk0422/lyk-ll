package com.example.starter.marpol.service;

import com.example.starter.marpol.domain.DischargeType;
import com.example.starter.marpol.domain.EmissionRecord;
import com.example.starter.marpol.domain.EmissionRequest;
import com.example.starter.marpol.domain.RecordStatus;
import com.example.starter.marpol.domain.Voyage;
import com.example.starter.marpol.repo.EmissionRecordRepository;
import com.example.starter.marpol.repo.ShipRepository;
import com.example.starter.marpol.support.ApiException;
import com.example.starter.marpol.support.TimeService;
import com.example.starter.marpol.web.DischargeAmendRequest;
import com.example.starter.marpol.web.DischargeSubmitRequest;
import com.example.starter.marpol.web.RecordResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排放记录提交、版本化修正与查询。
 *
 * <p>提交以 requestId 幂等（指纹覆盖 requestId 与全部业务字段）；
 * 修正生成新版本并引用原记录，原版本保留可查，查询默认返回最新 ACTIVE 版本；
 * 并发修正依靠 CAS + (record_no, version) 唯一约束保证仅一个胜出。
 */
@Service
public class EmissionService {

    private final EmissionRecordRepository recordRepository;
    private final ShipRepository shipRepository;
    private final ComplianceService complianceService;
    private final TimeService timeService;

    public EmissionService(EmissionRecordRepository recordRepository,
                           ShipRepository shipRepository,
                           ComplianceService complianceService,
                           TimeService timeService) {
        this.recordRepository = recordRepository;
        this.shipRepository = shipRepository;
        this.complianceService = complianceService;
        this.timeService = timeService;
    }

    /**
     * 提交一条新排放记录（幂等）。
     */
    @Transactional
    public RecordResponse submit(DischargeSubmitRequest request) {
        DischargeType type = parseType(request.dischargeType());
        String fingerprint = Fingerprint.submit(request.requestId(), request.shipId(),
                request.voyageNo(), type.name(), request.latitude(), request.longitude(),
                request.eventTime(), request.quantity(), request.unit());

        EmissionRequest existing = recordRepository.findRequest(request.requestId()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        // 航次关闭后新增记录一律拒绝
        Voyage voyage = requireVoyage(request.shipId(), request.voyageNo());
        if (voyage.isClosed()) {
            throw ApiException.conflict("航次 " + request.voyageNo() + " 已关闭，禁止新增排放记录");
        }

        EmissionRecord record = buildRecord(type, request.shipId(), request.voyageNo(),
                request.latitude(), request.longitude(), request.eventTime(), request.quantity(),
                request.unit());
        record.setRecordNo(generateRecordNo());
        record.setVersion(1);
        record.setRequestId(request.requestId());
        record.setStatus(RecordStatus.ACTIVE);
        applyCompliance(record);
        record.setCreatedAt(timeService.now());

        EmissionRequest registration = new EmissionRequest();
        registration.setRequestId(request.requestId());
        registration.setFingerprint(fingerprint);
        registration.setAction("SUBMIT");
        registration.setRecordNo(record.getRecordNo());
        registration.setVersion(1);
        registration.setCreatedAt(timeService.now());
        try {
            // 先登记幂等键：request_id 主键冲突即同键并发，由先提交者胜出，不产生孤儿版本
            recordRepository.insertRequest(registration);
        } catch (DuplicateKeyException e) {
            EmissionRequest winner = recordRepository.findRequest(request.requestId())
                    .orElseThrow(() -> ApiException.conflict("重复的 requestId 且参数不一致"));
            return replayOrConflict(winner, fingerprint);
        }
        recordRepository.insertVersion(record);
        return toResponse(record, false);
    }

    /**
     * 修正记录：生成新版本并引用原记录，原版本置 SUPERSEDED 保留可查。
     * 航次关闭不禁止对既有记录的修正（迟到修正仍可能发生在核查阶段）。
     */
    @Transactional
    public RecordResponse amend(DischargeAmendRequest request) {
        DischargeType type = parseType(request.dischargeType());
        String fingerprint = Fingerprint.amend(request.requestId(), request.recordNo(),
                request.basedOnVersion(), request.shipId(), request.voyageNo(), type.name(),
                request.latitude(), request.longitude(), request.eventTime(),
                request.quantity(), request.unit());

        EmissionRequest existing = recordRepository.findRequest(request.requestId()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        // 以请求声明的“所基于版本行”为锁锚点：并发修正都基于同一版本时阻塞在同一行；
        // 首个事务提交后该行变为 SUPERSEDED，后来者持锁后复验失败即返回 409，
        // 而基于更新版本的顺序修正锚定另一行，不受影响。
        if (request.basedOnVersion() == null || request.basedOnVersion() < 1) {
            throw ApiException.badRequest("basedOnVersion 必须为不小于 1 的版本号");
        }
        final int baseVersion = request.basedOnVersion();
        EmissionRecord active = recordRepository
                .findVersionForUpdate(request.recordNo(), baseVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "记录版本不存在: " + request.recordNo() + "#" + baseVersion));
        if (active.getStatus() != RecordStatus.ACTIVE) {
            throw ApiException.conflict("记录 " + request.recordNo() + " 的版本 " + baseVersion
                    + " 已被并发修正，请基于最新版本重试");
        }
        if (!active.getShipId().equals(request.shipId())
                || !active.getVoyageNo().equals(request.voyageNo())) {
            throw ApiException.badRequest("修正不得变更船舶或航次");
        }

        EmissionRecord revised = buildRecord(type, request.shipId(), request.voyageNo(),
                request.latitude(), request.longitude(), request.eventTime(), request.quantity(),
                request.unit());
        revised.setRecordNo(active.getRecordNo());
        int newVersion = active.getVersion() + 1;
        revised.setVersion(newVersion);
        revised.setRequestId(request.requestId());
        revised.setStatus(RecordStatus.ACTIVE);
        revised.setSupersedesRecordNo(active.getRecordNo());
        revised.setSupersedesVersion(active.getVersion());
        applyCompliance(revised);
        revised.setCreatedAt(timeService.now());

        // CAS：仅当前 ACTIVE 版本可被取代；并发修正只有一个胜出
        int superseded = recordRepository.markSuperseded(active.getRecordNo(),
                active.getVersion());
        if (superseded == 0) {
            throw ApiException.conflict("记录 " + request.recordNo()
                    + " 已被并发修正，请基于最新版本重试");
        }
        try {
            recordRepository.insertVersion(revised);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("记录 " + request.recordNo()
                    + " 的版本 " + newVersion + " 已被并发修正占用");
        }

        EmissionRequest registration = new EmissionRequest();
        registration.setRequestId(request.requestId());
        registration.setFingerprint(fingerprint);
        registration.setAction("AMEND");
        registration.setRecordNo(revised.getRecordNo());
        registration.setVersion(newVersion);
        registration.setCreatedAt(timeService.now());
        try {
            recordRepository.insertRequest(registration);
        } catch (DuplicateKeyException e) {
            EmissionRequest winner = recordRepository.findRequest(request.requestId())
                    .orElseThrow(() -> ApiException.conflict("重复的 requestId 且参数不一致"));
            return replayOrConflict(winner, fingerprint);
        }
        return toResponse(revised, false);
    }

    /**
     * 查询：默认返回最新有效版本；指定 version 返回历史版本。
     */
    @Transactional(readOnly = true)
    public RecordResponse getRecord(String recordNo, Integer version) {
        EmissionRecord record = version == null
                ? recordRepository.findActiveVersion(recordNo).orElse(null)
                : recordRepository.findVersion(recordNo, version).orElse(null);
        if (record == null) {
            throw ApiException.notFound("记录版本不存在: " + recordNo
                    + (version == null ? "（最新）" : "#" + version));
        }
        return toResponse(record, false);
    }

    @Transactional(readOnly = true)
    public List<RecordResponse> listVersions(String recordNo) {
        List<EmissionRecord> versions = recordRepository.findVersions(recordNo);
        if (versions.isEmpty()) {
            throw ApiException.notFound("记录不存在: " + recordNo);
        }
        return versions.stream().map(r -> toResponse(r, false)).toList();
    }

    @Transactional(readOnly = true)
    public List<RecordResponse> listActive(String shipId, String voyageNo) {
        return recordRepository.listActive(shipId, voyageNo).stream()
                .map(r -> toResponse(r, false))
                .toList();
    }

    private RecordResponse replayOrConflict(EmissionRequest existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId=" + existing.getRequestId()
                    + " 已用于不同参数的请求（同键异参，拒绝）");
        }
        EmissionRecord stored = recordRepository
                .findVersion(existing.getRecordNo(), existing.getVersion())
                .orElseThrow(() -> ApiException.notFound("幂等登记指向的记录版本不存在"));
        return toResponse(stored, true);
    }

    private void applyCompliance(EmissionRecord record) {
        ComplianceResult result = complianceService.evaluate(record.getDischargeType(),
                record.getLatitude(), record.getLongitude());
        record.setCompliant(result.compliant());
        record.setComplianceDetail(result.detail());
    }

    private EmissionRecord buildRecord(DischargeType type, String shipId, String voyageNo,
                                       java.math.BigDecimal latitude,
                                       java.math.BigDecimal longitude,
                                       java.time.Instant eventTime,
                                       java.math.BigDecimal quantity, String unit) {
        EmissionRecord record = new EmissionRecord();
        record.setDischargeType(type);
        record.setShipId(shipId);
        record.setVoyageNo(voyageNo);
        record.setLatitude(latitude);
        record.setLongitude(longitude);
        record.setEventTime(eventTime);
        record.setQuantity(quantity);
        record.setUnit(unit);
        return record;
    }

    private Voyage requireVoyage(String shipId, String voyageNo) {
        if (shipRepository.findShip(shipId).isEmpty()) {
            throw ApiException.notFound("船舶不存在: " + shipId);
        }
        return shipRepository.findVoyage(shipId, voyageNo)
                .orElseThrow(() -> ApiException.notFound(
                        "航次不存在: " + shipId + "/" + voyageNo));
    }

    private DischargeType parseType(String raw) {
        try {
            return DischargeType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "dischargeType 仅支持 OIL / GARBAGE / SEWAGE: " + raw);
        }
    }

    private String generateRecordNo() {
        return "ER-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private RecordResponse toResponse(EmissionRecord r, boolean replay) {
        return new RecordResponse(r.getRecordNo(), r.getVersion(), r.getRequestId(),
                r.getShipId(), r.getVoyageNo(), r.getDischargeType().name(), r.getLatitude(),
                r.getLongitude(), r.getEventTime(), r.getQuantity(), r.getUnit(), r.isCompliant(),
                r.getComplianceDetail(), r.getStatus().name(), r.getSupersedesRecordNo(),
                r.getSupersedesVersion(), r.getCreatedAt(), replay);
    }
}
