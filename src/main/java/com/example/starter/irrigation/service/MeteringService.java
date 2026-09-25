package com.example.starter.irrigation.service;

import com.example.starter.irrigation.api.MeteringDtos.MeterReportRequest;
import com.example.starter.irrigation.api.MeteringDtos.MeterSnapshotResponse;
import com.example.starter.irrigation.repo.GroupRepository.MemberRow;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.MeteringRepository.MeterEventRow;
import com.example.starter.irrigation.repo.MeteringRepository.MeterRow;
import com.example.starter.irrigation.repo.SettlementRepository;
import com.example.starter.irrigation.support.TurnClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实供计量服务。
 *
 * <p>去重规则：事件以 eventId 幂等，重复事件返回首次结果且不重复累计；
 * 计量快照按“斗口＋轮次”唯一，新确认读数覆盖旧读数（读数语义为本轮累计值）。
 * 封存终态后到达的计量只登记迟到事件，绝不修改计量快照与结转结果。
 * 同组事务通过组行锁串行，保证封存所见为该轮最后一次已确认快照。
 */
@Service
public class MeteringService {

    private final MeteringRepository meteringRepository;
    private final SettlementRepository settlementRepository;
    private final com.example.starter.irrigation.repo.GroupRepository groupRepository;
    private final TurnClock turnClock;
    private final Clock clock;

    public MeteringService(MeteringRepository meteringRepository,
                           SettlementRepository settlementRepository,
                           com.example.starter.irrigation.repo.GroupRepository groupRepository,
                           TurnClock turnClock, Clock clock) {
        this.meteringRepository = meteringRepository;
        this.settlementRepository = settlementRepository;
        this.groupRepository = groupRepository;
        this.turnClock = turnClock;
        this.clock = clock;
    }

    /** 上报一次计量确认。 */
    @Transactional
    public MeterSnapshotResponse report(MeterReportRequest request) {
        long turnNo = request.turnNo() != null ? request.turnNo() : turnClock.currentTurnNo();
        requireMember(request.groupCode(), request.outletCode());

        // 事件级幂等检查（唯一索引兜底并发重放）。
        MeterEventRow existingEvent = meteringRepository.findEvent(request.eventId()).orElse(null);
        if (existingEvent != null) {
            return buildResponseForExistingEvent(existingEvent);
        }

        // 组行锁：与封存、加购事务串行。
        meteringRepository.lockGroup(request.groupCode());

        // 锁内复查事件，消除检查与加锁间隙的并发重放。
        existingEvent = meteringRepository.findEvent(request.eventId()).orElse(null);
        if (existingEvent != null) {
            return buildResponseForExistingEvent(existingEvent);
        }

        Instant now = clock.instant();
        boolean late = settlementRepository.find(turnNo, request.groupCode()).isPresent();
        if (late) {
            registerLateEvent(turnNo, request, now);
            MeterRow snapshot = meteringRepository
                    .findSnapshot(turnNo, request.outletCode())
                    .orElse(null);
            // 迟到且无已确认快照：实供 0、确认时间为空（事件未被接受）。
            return new MeterSnapshotResponse(turnNo, request.groupCode(), request.outletCode(),
                    snapshot == null ? 0L : snapshot.deliveredCubicMeter(),
                    snapshot == null ? 0 : snapshot.eventCount(),
                    false, true, snapshot == null ? null : snapshot.confirmedAt());
        }

        registerConfirmedEvent(turnNo, request, now);
        MeterRow snapshot = meteringRepository
                .findSnapshot(turnNo, request.outletCode())
                .orElse(null);
        if (snapshot == null) {
            meteringRepository.insertSnapshot(turnNo, request.groupCode(), request.outletCode(),
                    request.deliveredCubicMeter(), now);
        } else {
            meteringRepository.updateSnapshot(turnNo, request.outletCode(),
                    request.deliveredCubicMeter(), now);
        }
        MeterRow saved = meteringRepository
                .findSnapshot(turnNo, request.outletCode())
                .orElseThrow(() -> new IllegalStateException("计量快照落库后查询为空"));
        return toResponse(saved, false, false);
    }

    private void registerConfirmedEvent(long turnNo, MeterReportRequest request, Instant now) {
        try {
            meteringRepository.insertEvent(new MeterEventRow(request.eventId(), turnNo,
                    request.groupCode(), request.outletCode(), request.deliveredCubicMeter()), now);
        } catch (DuplicateKeyException e) {
            // 并发重放竞态兜底：由调用方锁内复查逻辑处理。
            throw new ConflictException("计量事件已存在: " + request.eventId());
        }
    }

    private void registerLateEvent(long turnNo, MeterReportRequest request, Instant now) {
        try {
            meteringRepository.insertLateEvent(turnNo, request.groupCode(), request.outletCode(),
                    request.eventId(), request.deliveredCubicMeter(), now);
        } catch (DuplicateKeyException e) {
            // 同一迟到事件重放：幂等返回，不重复登记。
        }
    }

    private MeterSnapshotResponse buildResponseForExistingEvent(MeterEventRow event) {
        boolean late = meteringRepository.existsLateEvent(event.eventId());
        MeterRow snapshot = meteringRepository
                .findSnapshot(event.turnNo(), event.outletCode())
                .orElse(null);
        // 迟到事件不落账：无快照时实供以 0 计，不能回显事件上报读数。
        long delivered = snapshot != null ? snapshot.deliveredCubicMeter()
                : (late ? 0L : event.deliveredCubicMeter());
        return new MeterSnapshotResponse(event.turnNo(), event.groupCode(), event.outletCode(),
                delivered,
                snapshot == null ? 0 : snapshot.eventCount(),
                true, late, snapshot == null ? null : snapshot.confirmedAt());
    }

    private void requireMember(String groupCode, String outletCode) {
        List<MemberRow> members = groupRepository.findMembers(groupCode);
        if (members.isEmpty()) {
            throw new NotFoundException("轮灌组不存在: " + groupCode);
        }
        boolean member = members.stream().anyMatch(m -> m.outletCode().equals(outletCode));
        if (!member) {
            throw new BusinessRuleException("斗口不属于该轮灌组: group=" + groupCode
                    + ", outlet=" + outletCode);
        }
    }

    private MeterSnapshotResponse toResponse(MeterRow row, boolean duplicate, boolean late) {
        return new MeterSnapshotResponse(row.turnNo(), row.groupCode(), row.outletCode(),
                row.deliveredCubicMeter(), row.eventCount(), duplicate, late, row.confirmedAt());
    }
}
