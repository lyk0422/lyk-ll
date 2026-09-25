package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.example.starter.domain.BlastZone;
import com.example.starter.domain.Mine;
import com.example.starter.domain.Permit;
import com.example.starter.domain.PermitStatus;
import com.example.starter.dto.PermitRequest;
import com.example.starter.dto.PermitResponse;
import com.example.starter.error.ApiException;
import com.example.starter.repository.MineRepository;
import com.example.starter.repository.PermitRepository;
import com.example.starter.repository.ZoneRepository;
import com.example.starter.util.Fingerprints;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 爆破作业申请与审批服务。
 * 允许作业时段为每日 06:00（含）至 22:00（不含，UTC）。
 */
@Service
public class PermitService {

    private static final int WINDOW_OPEN_HOUR = 6;
    private static final int WINDOW_CLOSE_HOUR = 22;

    private final PermitRepository permits;
    private final ZoneRepository zones;
    private final MineRepository mines;
    private final Clock clock;

    public PermitService(PermitRepository permits, ZoneRepository zones,
            MineRepository mines, Clock businessClock) {
        this.permits = permits;
        this.zones = zones;
        this.mines = mines;
        this.clock = businessClock;
    }

    /**
     * 提交申请：幂等；时段、药量上限、矿区冻结任一不满足则落库为 REJECTED。
     */
    @Transactional
    public PermitResponse apply(PermitRequest request) {
        String fingerprint = fingerprintOf(request);
        var existing = permits.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Permit found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return PermitResponse.from(found);
        }

        BlastZone zone = zones.findById(request.zoneId())
                .orElseThrow(() -> ApiException.notFound("爆区不存在: " + request.zoneId()));
        Mine mine = mines.findById(zone.mineId())
                .orElseThrow(() -> ApiException.notFound("矿区不存在: " + zone.mineId()));

        PermitStatus status = PermitStatus.PENDING;
        String reason = null;
        if (mine.frozen()) {
            status = PermitStatus.REJECTED;
            reason = "矿区冻结中，申请驳回";
        } else if (!withinWindow(request.plannedBlastTime())) {
            status = PermitStatus.REJECTED;
            reason = "计划起爆时刻不在允许时段 06:00-22:00(UTC)";
        } else if (request.chargePerDelay().compareTo(zone.maxChargePerDelay()) > 0) {
            status = PermitStatus.REJECTED;
            reason = "单段药量超过爆区备案上限 " + zone.maxChargePerDelay() + " kg";
        }

        Permit permit = new Permit(0L, request.requestId(), fingerprint, zone.id(),
                request.applicant(), request.plannedBlastTime(), request.chargePerDelay(),
                status, reason);
        long id;
        try {
            id = permits.insert(permit, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发同键插入：回读并按幂等规则处理
            Permit raced = permits.findByRequestId(request.requestId())
                    .orElseThrow(() -> e);
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return PermitResponse.from(raced);
        }
        return PermitResponse.from(permits.findById(id).orElseThrow());
    }

    /**
     * 审批：仅在途申请可批准；同一爆区同一时刻仅一个已批准作业（槽位主键保证并发唯一）。
     */
    @Transactional
    public PermitResponse approve(long permitId) {
        Permit permit = permits.findById(permitId)
                .orElseThrow(() -> ApiException.notFound("申请单不存在: " + permitId));
        if (permit.status() != PermitStatus.PENDING) {
            throw ApiException.unprocessable("ILLEGAL_STATE",
                    "仅在途申请可审批，当前状态 " + permit.status());
        }
        BlastZone zone = zones.findById(permit.zoneId()).orElseThrow();
        Mine mine = mines.findById(zone.mineId()).orElseThrow();
        if (mine.frozen()) {
            throw ApiException.unprocessable("MINE_FROZEN", "矿区冻结中，禁止审批");
        }
        int updated = permits.updateStatus(permitId, PermitStatus.PENDING,
                PermitStatus.APPROVED, null, Instant.now(clock));
        if (updated == 0) {
            throw ApiException.conflict("申请单状态已变更，审批失败");
        }
        try {
            permits.occupySlot(permit.zoneId(), permit.plannedBlastTime(), permitId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一爆区同一时刻已存在已批准作业");
        }
        return PermitResponse.from(permits.findById(permitId).orElseThrow());
    }

    /**
     * 查询申请单。
     */
    @Transactional(readOnly = true)
    public PermitResponse get(long permitId) {
        return permits.findById(permitId)
                .map(PermitResponse::from)
                .orElseThrow(() -> ApiException.notFound("申请单不存在: " + permitId));
    }

    /**
     * 时段判断：06:00（含）至 22:00（不含，UTC）。
     */
    static boolean withinWindow(Instant planned) {
        int hour = planned.atOffset(ZoneOffset.UTC).getHour();
        return hour >= WINDOW_OPEN_HOUR && hour < WINDOW_CLOSE_HOUR;
    }

    private static String fingerprintOf(PermitRequest r) {
        return Fingerprints.of(r.requestId(), String.valueOf(r.zoneId()), r.applicant(),
                r.plannedBlastTime().toString(), r.chargePerDelay().stripTrailingZeros().toPlainString());
    }
}
