package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.BlastZone;
import com.example.starter.domain.Mine;
import com.example.starter.domain.Permit;
import com.example.starter.domain.PermitStatus;
import com.example.starter.dto.ReadingRequest;
import com.example.starter.dto.ReadingResponse;
import com.example.starter.error.ApiException;
import com.example.starter.repository.MineRepository;
import com.example.starter.repository.PermitRepository;
import com.example.starter.repository.ReadingRepository;
import com.example.starter.repository.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 振动监测回传服务：按爆区+测点+采样时刻去重；
 * 任一测点振速超过矿区阈值立即触发冻结，冻结在同一事务内生效。
 */
@Service
public class VibrationService {

    private final ReadingRepository readings;
    private final PermitRepository permits;
    private final ZoneRepository zones;
    private final MineRepository mines;
    private final Clock clock;

    public VibrationService(ReadingRepository readings, PermitRepository permits,
            ZoneRepository zones, MineRepository mines, Clock businessClock) {
        this.readings = readings;
        this.permits = permits;
        this.zones = zones;
        this.mines = mines;
        this.clock = businessClock;
    }

    /**
     * 接收一条振动读数。
     */
    @Transactional
    public ReadingResponse submit(ReadingRequest request) {
        Permit permit = permits.findById(request.permitId())
                .orElseThrow(() -> ApiException.notFound("申请单不存在: " + request.permitId()));
        BlastZone zone = zones.findById(permit.zoneId()).orElseThrow();
        Mine mine = mines.findById(zone.mineId()).orElseThrow();
        Instant now = Instant.now(clock);

        if (permit.status() == PermitStatus.SEALED) {
            // 迟到读数：只记迟到事件，不改评估结论
            readings.insertLateEvent(permit.id(), zone.id(), request.pointCode(),
                    request.sampledAt(), now);
            return new ReadingResponse(permit.id(), request.pointCode(), false, false, true, false);
        }
        if (permit.status() != PermitStatus.FILED) {
            throw ApiException.unprocessable("ILLEGAL_STATE",
                    "仅已起爆备案的作业可回传振动数据，当前状态 " + permit.status());
        }

        boolean inserted = readings.insertIfAbsent(permit.id(), zone.id(), request.pointCode(),
                request.sampledAt(), request.velocity(), now);
        if (!inserted) {
            return new ReadingResponse(permit.id(), request.pointCode(), false, true, false, false);
        }

        boolean freezeTriggered = false;
        if (!mine.frozen() && request.velocity().compareTo(mine.velocityThreshold()) > 0) {
            String reason = "测点 " + request.pointCode() + " 振速 " + request.velocity()
                    + " cm/s 超过阈值 " + mine.velocityThreshold() + " cm/s";
            mines.freeze(mine.id(), now, reason);
            permits.rejectPendingByMine(mine.id(), "矿区冻结：在途申请驳回", now);
            permits.voidApprovedByMine(mine.id(), "矿区冻结：已批准未起爆作业作废", now);
            freezeTriggered = true;
        }
        return new ReadingResponse(permit.id(), request.pointCode(), true, false, false,
                freezeTriggered);
    }
}
