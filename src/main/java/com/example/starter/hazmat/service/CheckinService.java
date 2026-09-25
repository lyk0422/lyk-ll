package com.example.starter.hazmat.service;

import com.example.starter.hazmat.domain.AlertType;
import com.example.starter.hazmat.domain.CheckinResult;
import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.domain.WaypointStatus;
import com.example.starter.hazmat.error.BusinessException;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.error.NotFoundException;
import com.example.starter.hazmat.model.CheckinRecord;
import com.example.starter.hazmat.model.Waybill;
import com.example.starter.hazmat.model.WaybillWaypoint;
import com.example.starter.hazmat.repo.AlertRepository;
import com.example.starter.hazmat.repo.CheckinRepository;
import com.example.starter.hazmat.repo.VehicleFreezeRepository;
import com.example.starter.hazmat.repo.WaybillRepository;
import com.example.starter.hazmat.repo.WaybillWaypointRepository;
import com.example.starter.hazmat.web.CheckinView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 押运打卡服务：按“运单＋途经点序号”去重；乱序回传按时刻归位，逆序拒绝并告警；
 * 计划通过时刻 + 60 分钟（恰满 60 分钟即判超时）未打卡标记偏离预警，
 * 连续 2 点超时升级失联并冻结车辆。结案后迟到打卡仅记迟到事件。
 */
@Service
public class CheckinService {

    /** 超时阈值分钟数，恰满 60 分钟判定为超时。 */
    public static final int TIMEOUT_MINUTES = 60;

    private final CheckinRepository checkins;
    private final WaybillRepository waybills;
    private final WaybillWaypointRepository waypoints;
    private final AlertRepository alerts;
    private final VehicleFreezeRepository freezes;
    private final Clock clock;

    public CheckinService(CheckinRepository checkins, WaybillRepository waybills,
                          WaybillWaypointRepository waypoints, AlertRepository alerts,
                          VehicleFreezeRepository freezes, Clock clock) {
        this.checkins = checkins;
        this.waybills = waybills;
        this.waypoints = waypoints;
        this.alerts = alerts;
        this.freezes = freezes;
        this.clock = clock;
    }

    /**
     * 押运打卡，requestId 幂等。逆序拒绝时告警记录随异常提交保留（可复算）。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public CheckinView checkin(String requestId, long waybillId, int seq,
                               LocalDateTime checkinTime) {
        String fingerprint = Fingerprints.of(
                "CHECKIN", requestId, waybillId, seq, checkinTime);
        CheckinRecord replay = checkins.findByRequestId(requestId).orElse(null);
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已存在但业务参数不一致: " + requestId);
            }
            return viewOf(replay, true, replay.message());
        }
        Waybill waybill = waybills.findById(waybillId)
                .orElseThrow(() -> new NotFoundException("运单不存在: " + waybillId));
        int gen = waybill.routeGeneration();
        if (checkins.findByWaybillGenSeq(waybillId, gen, seq).isPresent()) {
            throw new ConflictException(String.format(
                    "运单 %d 途经点 %d 已打卡，按运单+途经点序号去重", waybillId, seq));
        }
        WaybillWaypoint waypoint = waypoints.findByWaybillGenSeq(waybillId, gen, seq)
                .orElseThrow(() -> new NotFoundException(String.format(
                        "运单 %d 当前路线无途经点序号 %d", waybillId, seq)));
        LocalDateTime now = LocalDateTime.now(clock);

        if (waybill.status() == WaybillStatus.CLOSED) {
            return recordLateAfterClose(requestId, fingerprint, waybill, gen, seq, checkinTime, now);
        }
        if (waybill.status() == WaybillStatus.LOST) {
            throw new ConflictException("运单已失联冻结，不再受理打卡: " + waybillId);
        }
        if (waybill.status() != WaybillStatus.IN_TRANSIT) {
            throw new BusinessException("仅在途运单可以打卡，当前状态: " + waybill.status());
        }

        assertTimeOrder(waybillId, gen, seq, checkinTime, now);

        int rows;
        try {
            rows = waypoints.markChecked(waybillId, gen, seq, checkinTime);
            if (rows > 0) {
                checkins.insert(requestId, fingerprint, waybillId, gen, seq, checkinTime,
                        CheckinResult.ACCEPTED, null, now);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException(String.format(
                    "运单 %d 途经点 %d 打卡并发冲突（重复打卡）", waybillId, seq));
        }
        if (rows == 0) {
            throw new ConflictException(String.format(
                    "运单 %d 途经点 %d 状态已变化，打卡冲突", waybillId, seq));
        }
        String escalation = evaluateTimeouts(waybill, gen, now);
        String message = escalation == null ? null : escalation;
        return new CheckinView(requestId, waybillId, seq, checkinTime, true,
                CheckinResult.ACCEPTED, message);
    }

    /**
     * 结案后的迟到打卡：只记录迟到事件（打卡记录 + 告警），不改变终态。
     */
    private CheckinView recordLateAfterClose(String requestId, String fingerprint, Waybill waybill,
                                             int gen, int seq, LocalDateTime checkinTime,
                                             LocalDateTime now) {
        try {
            checkins.insert(requestId, fingerprint, waybill.id(), gen, seq, checkinTime,
                    CheckinResult.LATE_AFTER_CLOSE, "结案后迟到打卡，仅记录事件", now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(String.format(
                    "运单 %d 途经点 %d 已存在打卡事件", waybill.id(), seq));
        }
        alerts.insert(waybill.id(), waybill.vehicleCode(), AlertType.LATE_AFTER_CLOSE,
                String.format("结案后迟到打卡：途经点 %d，打卡时刻 %s", seq, checkinTime), now);
        return new CheckinView(requestId, waybill.id(), seq, checkinTime, true,
                CheckinResult.LATE_AFTER_CLOSE, "结案后迟到打卡，仅记录事件");
    }

    /**
     * 打卡时序校验：乱序回传按时刻归位，新打卡时刻须严格晚于所有已打卡的前序途经点、
     * 严格早于所有已打卡的后序途经点；逆序拒绝并写入 ORDER_VIOLATION 告警。
     */
    private void assertTimeOrder(long waybillId, int gen, int seq, LocalDateTime checkinTime,
                                 LocalDateTime now) {
        List<WaybillWaypoint> checked = waypoints.findCurrentByWaybill(waybillId, gen).stream()
                .filter(wp -> wp.status() == WaypointStatus.CHECKED)
                .toList();
        for (WaybillWaypoint other : checked) {
            boolean inverse = other.seq() < seq
                    ? !checkinTime.isAfter(other.checkinTime())
                    : !checkinTime.isBefore(other.checkinTime());
            if (inverse) {
                String message = String.format(
                        "打卡逆序拒绝：途经点 %d 打卡时刻 %s 与已打卡途经点 %d（时刻 %s）时序冲突",
                        seq, checkinTime, other.seq(), other.checkinTime());
                alerts.insert(waybillId, vehicleOf(waybillId), AlertType.ORDER_VIOLATION,
                        message, now);
                throw new BusinessException(message);
            }
        }
    }

    private String vehicleOf(long waybillId) {
        return waybills.findById(waybillId).map(Waybill::vehicleCode).orElse("UNKNOWN");
    }

    /**
     * 按给定时刻复算超时：PENDING 途经点在计划通过时刻 + 60 分钟（含恰满 60 分钟）仍未打卡
     * 则标记 TIMEOUT 并写偏离预警；出现连续 2 个 TIMEOUT 时升级失联、冻结车辆。
     * 返回升级说明；未升级返回 null。
     */
    public String evaluateTimeouts(Waybill waybill, int gen, LocalDateTime now) {
        List<WaybillWaypoint> open = waypoints.findOpen(waybill.id(), gen);
        for (WaybillWaypoint wp : open) {
            if (wp.status() != WaypointStatus.PENDING) {
                continue;
            }
            LocalDateTime deadline = wp.plannedPassTime().plusMinutes(TIMEOUT_MINUTES);
            if (!now.isBefore(deadline)) {
                int marked = waypoints.markTimeout(waybill.id(), gen, wp.seq());
                if (marked > 0) {
                    alerts.insert(waybill.id(), waybill.vehicleCode(), AlertType.DEVIATION,
                            String.format("途经点 %d 超过计划通过时刻 %d 分钟未打卡，偏离预警",
                                    wp.seq(), TIMEOUT_MINUTES),
                            now);
                }
            }
        }
        if (waybills.findById(waybill.id()).map(Waybill::status).orElse(null)
                != WaybillStatus.IN_TRANSIT) {
            return null;
        }
        List<WaybillWaypoint> current = waypoints.findCurrentByWaybill(waybill.id(), gen);
        for (int i = 1; i < current.size(); i++) {
            if (current.get(i - 1).status() == WaypointStatus.TIMEOUT
                    && current.get(i).status() == WaypointStatus.TIMEOUT) {
                return escalateLost(waybill, current.get(i - 1).seq(), current.get(i).seq(), now);
            }
        }
        return null;
    }

    private String escalateLost(Waybill waybill, int firstSeq, int secondSeq, LocalDateTime now) {
        int rows = waybills.transition(waybill.id(), WaybillStatus.IN_TRANSIT,
                WaybillStatus.LOST, now, true);
        if (rows == 0) {
            return null;
        }
        alerts.insert(waybill.id(), waybill.vehicleCode(), AlertType.LOST,
                String.format("途经点 %d、%d 连续超时未打卡，升级失联并冻结车辆 %s",
                        firstSeq, secondSeq, waybill.vehicleCode()),
                now);
        freezes.freeze(waybill.vehicleCode(), waybill.id(),
                String.format("运单 %d 连续 2 个途经点超时，升级失联", waybill.id()), now);
        return String.format("途经点 %d、%d 连续超时，已升级失联并冻结车辆 %s",
                firstSeq, secondSeq, waybill.vehicleCode());
    }

    private CheckinView viewOf(CheckinRecord record, boolean accepted, String message) {
        return new CheckinView(record.requestId(), record.waybillId(), record.waypointSeq(),
                record.checkinTime(), accepted, record.result(), message);
    }
}
