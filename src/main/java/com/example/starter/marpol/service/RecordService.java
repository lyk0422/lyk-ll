package com.example.starter.marpol.service;

import com.example.starter.marpol.domain.RecordType;
import com.example.starter.marpol.domain.RecordVersionStatus;
import com.example.starter.marpol.error.ConflictException;
import com.example.starter.marpol.error.NotFoundException;
import com.example.starter.marpol.error.RuleViolationException;
import com.example.starter.marpol.model.EmissionRecord;
import com.example.starter.marpol.model.LandMark;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.SpecialArea;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.repo.EmissionRecordRepository;
import com.example.starter.marpol.repo.ReferenceRepository;
import com.example.starter.marpol.support.Fingerprint;
import com.example.starter.marpol.support.GeoUtils;
import com.example.starter.marpol.support.ServiceClock;
import com.example.starter.marpol.web.CorrectRecordRequest;
import com.example.starter.marpol.web.RecordResponse;
import com.example.starter.marpol.web.SubmitRecordRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 排放记录领域服务：规则校验、requestId 幂等、版本化修正。
 *
 * <p>并发口径：</p>
 * <ul>
 *   <li>同 requestId 唯一由 uk_emission_request 保证；同键异参返回 409；</li>
 *   <li>同 root 版本号唯一由 uk_emission_root_version 保证，并发修正只有一个胜出，败者 409。</li>
 * </ul>
 */
@Service
public class RecordService {

    private final EmissionRecordRepository records;
    private final ReferenceRepository refs;
    private final ServiceClock clock;
    private final double garbageMinDistanceNm;

    public RecordService(EmissionRecordRepository records,
                         ReferenceRepository refs,
                         ServiceClock clock,
                         @Value("${marpol.garbage-min-distance-nm:12}") double garbageMinDistanceNm) {
        this.records = records;
        this.refs = refs;
        this.clock = clock;
        this.garbageMinDistanceNm = garbageMinDistanceNm;
    }

    /**
     * 提交首版本排放记录。
     */
    @Transactional
    public EmissionRecord submit(SubmitRecordRequest req) {
        String fp = fingerprint(req.requestId(), req.type(), req.longitude(), req.latitude(),
                req.eventTime(), req.quantity(), req.unit());

        EmissionRecord existing = records.findByRequestId(req.requestId());
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }

        Ship ship = requireShip(req.shipImo());
        Voyage voyage = requireVoyage(ship, req.voyageNo());
        ensureVoyageOpen(voyage);
        RecordType type = parseType(req.type());
        validateGeoRules(type, req.longitude(), req.latitude());

        Instant now = clock.now();
        EmissionRecord draft = new EmissionRecord(
                null, req.requestId(), null, 1, null, ship.id(), voyage.id(), type,
                req.longitude(), req.latitude(), req.eventTime(), req.quantity(), req.unit(),
                RecordVersionStatus.ACTIVE, fp, now);
        try {
            Long id = records.insert(draft);
            // 首版本的版本族根 id 即其自身 id。
            records.bindRootToSelf(id);
            return records.findById(id);
        } catch (DuplicateKeyException e) {
            EmissionRecord raced = records.findByRequestId(req.requestId());
            if (raced != null) {
                return replayOrConflict(raced, fp);
            }
            throw new ConflictException("排放记录提交冲突，请重试");
        }
    }

    /**
     * 修正记录：在原版本族上生成新版本，原版本保留可查。
     */
    @Transactional
    public EmissionRecord correct(CorrectRecordRequest req) {
        String fp = fingerprint(req.requestId(), req.type(), req.longitude(), req.latitude(),
                req.eventTime(), req.quantity(), req.unit());

        EmissionRecord idempotent = records.findByRequestId(req.requestId());
        if (idempotent != null) {
            return replayOrConflict(idempotent, fp);
        }

        EmissionRecord referenced = records.findById(req.originalRecordId());
        if (referenced == null) {
            throw new NotFoundException("被修正记录不存在: " + req.originalRecordId());
        }

        // 非锁定读取版本族当前 ACTIVE，仅用于校验客户端指向的版本是否仍为最新；
        // 胜败由针对“客户端指定的具体版本行”的条件更新计数裁决，
        // 晚到线程不会接续到已胜出线程产生的新版本。
        EmissionRecord active = records.findActiveByRoot(referenced.rootId());
        if (active == null) {
            throw new ConflictException("记录版本族状态异常，无有效版本");
        }
        if (!active.id().equals(referenced.id())) {
            throw new ConflictException("被修正版本 " + referenced.versionNo()
                    + " 已不是最新有效版本（最新为 " + active.versionNo() + "），请基于最新版本修正");
        }
        Voyage voyage = requireVoyageById(active.voyageId());
        ensureVoyageOpen(voyage);
        RecordType type = parseType(req.type());
        validateGeoRules(type, req.longitude(), req.latitude());

        Instant now = clock.now();
        if (records.markSuperseded(referenced.id()) == 0) {
            throw new ConflictException("并发修正冲突：同一记录只允许一个新版本胜出（版本 "
                    + (referenced.versionNo() + 1) + "）");
        }
        EmissionRecord next = new EmissionRecord(
                null, req.requestId(), active.rootId(), active.versionNo() + 1, referenced.id(),
                active.shipId(), active.voyageId(), type,
                req.longitude(), req.latitude(), req.eventTime(), req.quantity(), req.unit(),
                RecordVersionStatus.ACTIVE, fp, now);
        try {
            Long id = records.insert(next);
            return records.findById(id);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("并发修正冲突：同一记录只允许一个新版本胜出（版本 "
                    + (active.versionNo() + 1) + "）");
        }
    }

    /**
     * 查询单条记录；默认返回最新有效版本，{@code version} 指定时返回原版本（含已取代版本）。
     */
    @Transactional(readOnly = true)
    public EmissionRecord get(Long id, Integer version) {
        EmissionRecord any = records.findById(id);
        if (any == null) {
            throw new NotFoundException("排放记录不存在: " + id);
        }
        if (version != null) {
            return records.findVersions(any.rootId()).stream()
                    .filter(v -> v.versionNo() == version)
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("版本不存在: record=" + id + ", version=" + version));
        }
        return records.findActiveByRoot(any.rootId());
    }

    /**
     * 构造对外视图（补充船舶 IMO 与航次号）。
     */
    @Transactional(readOnly = true)
    public RecordResponse toResponse(EmissionRecord r) {
        Voyage voyage = requireVoyageById(r.voyageId());
        Ship ship = refs.findShipById(r.shipId());
        return new RecordResponse(
                r.id(), r.requestId(), r.rootId(), r.versionNo(), r.supersedesId(),
                ship.imo(), voyage.voyageNo(), r.recordType().name(),
                r.longitude(), r.latitude(), r.eventTime(), r.quantity(), r.unit(),
                r.status().name(), r.createdAt());
    }

    private EmissionRecord replayOrConflict(EmissionRecord existing, String incomingFingerprint) {
        if (existing.fingerprint().equals(incomingFingerprint)) {
            return existing;
        }
        throw new ConflictException("requestId 已存在但业务参数不一致: " + existing.requestId());
    }

    private Ship requireShip(String imo) {
        Ship ship = refs.findShipByImo(imo);
        if (ship == null) {
            throw new NotFoundException("船舶不存在: " + imo);
        }
        return ship;
    }

    private Voyage requireVoyage(Ship ship, String voyageNo) {
        Voyage voyage = refs.findVoyage(ship.id(), voyageNo);
        if (voyage == null) {
            throw new NotFoundException("航次不存在: ship=" + ship.imo() + ", voyage=" + voyageNo);
        }
        return voyage;
    }

    private Voyage requireVoyageById(Long voyageId) {
        Voyage voyage = refs.findVoyageById(voyageId);
        if (voyage == null) {
            throw new NotFoundException("航次不存在: id=" + voyageId);
        }
        return voyage;
    }

    private void ensureVoyageOpen(Voyage voyage) {
        if (voyage.status() == com.example.starter.marpol.domain.VoyageStatus.CLOSED) {
            throw new RuleViolationException("航次已关闭，禁止新增排放记录/新版本: " + voyage.voyageNo());
        }
    }

    private RecordType parseType(String raw) {
        try {
            return RecordType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new RuleViolationException("不支持的排放类型: " + raw);
        }
    }

    /**
     * 区域规则：
     * <ul>
     *   <li>油类/生活污水：特殊区域（含边界线）内禁止；</li>
     *   <li>垃圾：距最近陆地须 ≥ 12 海里，恰 12 海里合法。</li>
     * </ul>
     */
    private void validateGeoRules(RecordType type, double lon, double lat) {
        List<SpecialArea> areas = refs.findAllSpecialAreas();
        if ((type == RecordType.OIL || type == RecordType.SEWAGE)) {
            for (SpecialArea area : areas) {
                if (GeoUtils.insideOrOnBoundary(lon, lat, area.polygon())) {
                    throw new RuleViolationException("特殊区域内（含边界）禁止排放 "
                            + type + "：" + area.name());
                }
            }
        } else {
            List<LandMark> lands = refs.findAllLandMarks();
            List<double[]> points = lands.stream()
                    .map(l -> new double[]{l.longitude(), l.latitude()})
                    .toList();
            double nearest = GeoUtils.nearestLandNm(lon, lat, points);
            if (nearest < garbageMinDistanceNm) {
                throw new RuleViolationException(String.format(
                        "垃圾排放点距最近陆地 %.3f 海里，少于 %.0f 海里", nearest, garbageMinDistanceNm));
            }
        }
    }

    /**
     * 幂等指纹：requestId + 全部业务字段（类型、经纬度、UTC 时刻、数量及单位）。
     */
    private String fingerprint(String requestId, String type, double lon, double lat,
                               Instant eventTime, BigDecimal quantity, String unit) {
        String canonical = String.join("|",
                requestId, type,
                BigDecimal.valueOf(lon).stripTrailingZeros().toPlainString(),
                BigDecimal.valueOf(lat).stripTrailingZeros().toPlainString(),
                eventTime.toString(),
                quantity.stripTrailingZeros().toPlainString(),
                unit);
        return Fingerprint.sha256(canonical);
    }
}
