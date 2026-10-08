package com.example.starter.service;

import com.example.starter.model.MaintenanceAcceptance;
import com.example.starter.model.MaintenanceAcceptanceRole;
import com.example.starter.model.MaintenanceClearance;
import com.example.starter.model.MaintenanceOrder;
import com.example.starter.model.MaintenanceOrderStatus;
import com.example.starter.model.WindLine;
import com.example.starter.model.WindSegment;
import com.example.starter.repo.LineRepository;
import com.example.starter.repo.MaintenanceRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceAcceptanceResponse;
import com.example.starter.web.dto.MaintenanceCancelRequest;
import com.example.starter.web.dto.MaintenanceCancelResponse;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceClearanceResponse;
import com.example.starter.web.dto.MaintenanceOrderDetailView;
import com.example.starter.web.dto.MaintenanceOrderModifyRequest;
import com.example.starter.web.dto.MaintenanceOrderRequest;
import com.example.starter.web.dto.MaintenanceOrderResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 检修封锁业务：检修单绑定线路与分段清单及 UTC 窗口 [start,end)，与风速管制相互独立。
 * 上客/复开门禁每次实时读取当前时刻判定生效态，不依赖后台扫描；
 * 登记检修单不会自动清空吊厢，生效后只允许按原能力逐段疏散留证，
 * 验收不得早于 end，且须机务、检修两个不同人员分别完成。
 */
@Service
public class MaintenanceService {

    private final LineRepository lineRepository;
    private final SegmentRepository segmentRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final IdempotencyService idempotencyService;
    private final ControlClock clock;
    private final JsonSupport json;

    public MaintenanceService(LineRepository lineRepository, SegmentRepository segmentRepository,
                              MaintenanceRepository maintenanceRepository,
                              IdempotencyService idempotencyService, ControlClock clock, JsonSupport json) {
        this.lineRepository = lineRepository;
        this.segmentRepository = segmentRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
        this.json = json;
    }

    /**
     * 登记检修单（版本自 1 起）：清单不能为空或跨线路；同线路窗口内部重叠拒绝，端点相接允许。
     */
    public ResponseEntity<String> createOrder(long lineId, MaintenanceOrderRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-order", String.valueOf(lineId),
                request.startAt().toString(), request.endAt().toString(), request.reason(),
                canonicalSegments(request));
        return idempotencyService.execute("maintenance-order", request.requestId(), fingerprint, () -> {
            WindLine line = lockLine(lineId);
            validateWindow(request.startAt(), request.endAt());
            List<Long> segmentIds = validateSegments(line.id(), request.segmentIds());
            if (maintenanceRepository.existsOverlappingScheduled(lineId,
                    request.startAt(), request.endAt(), null)) {
                throw ApiException.conflict("同线路检修窗口内部重叠，端点相接才允许");
            }
            long orderId = maintenanceRepository.insertOrder(lineId,
                    request.startAt(), request.endAt(), request.reason());
            maintenanceRepository.insertSegments(orderId, segmentIds);
            return json.ok(new MaintenanceOrderResponse(orderId, lineId, 1,
                    MaintenanceOrderStatus.SCHEDULED.name(), request.startAt(), request.endAt(),
                    request.reason(), segmentIds));
        });
    }

    /**
     * 修改检修单：expectedVersion 不一致返回 409，成功后版本加 1；窗口与清单规则同登记。
     */
    public ResponseEntity<String> modifyOrder(long orderId, MaintenanceOrderModifyRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-order-modify", String.valueOf(orderId),
                String.valueOf(request.expectedVersion()),
                request.startAt().toString(), request.endAt().toString(), request.reason(),
                canonicalSegments(request.segmentIds()));
        return idempotencyService.execute("maintenance-order-modify", request.requestId(), fingerprint, () -> {
            MaintenanceOrder order = lockOrderForWrite(orderId);
            requireScheduled(order);
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("检修单版本已过期: expectedVersion=" + request.expectedVersion()
                        + ", current=" + order.version());
            }
            validateWindow(request.startAt(), request.endAt());
            List<Long> segmentIds = validateSegments(order.lineId(), request.segmentIds());
            if (maintenanceRepository.existsOverlappingScheduled(order.lineId(),
                    request.startAt(), request.endAt(), orderId)) {
                throw ApiException.conflict("同线路检修窗口内部重叠，端点相接才允许");
            }
            int updated = maintenanceRepository.updateWindow(orderId, request.expectedVersion(),
                    request.startAt(), request.endAt(), request.reason());
            if (updated == 0) {
                throw ApiException.conflict("检修单版本已过期或状态已变化");
            }
            maintenanceRepository.deleteSegments(orderId);
            maintenanceRepository.insertSegments(orderId, segmentIds);
            MaintenanceOrder latest = maintenanceRepository.findById(orderId).orElseThrow();
            return json.ok(toOrderResponse(latest, segmentIds));
        });
    }

    /**
     * 取消检修单：仅未生效检修单可取消；expectedVersion 不一致返回 409。
     */
    public ResponseEntity<String> cancelOrder(long orderId, MaintenanceCancelRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-order-cancel", String.valueOf(orderId),
                String.valueOf(request.expectedVersion()));
        return idempotencyService.execute("maintenance-order-cancel", request.requestId(), fingerprint, () -> {
            MaintenanceOrder order = lockOrderForWrite(orderId);
            requireScheduled(order);
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("检修单版本已过期: expectedVersion=" + request.expectedVersion()
                        + ", current=" + order.version());
            }
            Instant now = clock.now();
            if (!now.isBefore(order.startAt())) {
                throw ApiException.conflict("检修已生效，不能取消，须逐段清空并双岗验收");
            }
            int updated = maintenanceRepository.cancel(orderId, request.expectedVersion(), now);
            if (updated == 0) {
                throw ApiException.conflict("检修单版本已过期或状态已变化");
            }
            return json.ok(new MaintenanceCancelResponse(orderId, order.version() + 1,
                    MaintenanceOrderStatus.CANCELLED.name(), now));
        });
    }

    /**
     * 登记分段清空证据：仅生效后允许，按原能力逐段疏散清空；登记检修单本身不产生清空结果。
     */
    public ResponseEntity<String> registerClearance(long orderId, MaintenanceClearanceRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-clearance", String.valueOf(orderId),
                String.valueOf(request.segmentId()), request.operatorId(), request.opinion());
        return idempotencyService.execute("maintenance-clearance", request.requestId(), fingerprint, () -> {
            MaintenanceOrder order = lockOrderForWrite(orderId);
            requireScheduled(order);
            Instant now = clock.now();
            if (!order.activeAt(now)) {
                throw ApiException.unprocessable("检修尚未生效，不能登记分段清空证据");
            }
            List<Long> listed = maintenanceRepository.listSegmentIds(orderId);
            if (!listed.contains(request.segmentId())) {
                throw ApiException.unprocessable("分段不在检修单清单内: segmentId=" + request.segmentId());
            }
            maintenanceRepository.insertClearance(orderId, request.segmentId(),
                    request.operatorId(), request.opinion(), now);
            List<MaintenanceClearance> clearances = maintenanceRepository.listClearances(orderId);
            MaintenanceClearance recorded = clearances.stream()
                    .filter(c -> c.segmentId() == request.segmentId())
                    .findFirst().orElseThrow();
            return json.ok(new MaintenanceClearanceResponse(orderId, request.segmentId(),
                    clearances.size(), listed.size(), clearances.size() >= listed.size(),
                    recorded.operatorId(), recorded.opinion(), recorded.createdAt()));
        });
    }

    /**
     * 检修验收：不得早于 end；须清单各段已清空，且机务、检修两个不同人员分别完成；
     * 恰 end 且条件齐全可以结束，到 end 未验收仍封锁。完成只解除检修自身阻断。
     */
    public ResponseEntity<String> accept(long orderId, MaintenanceAcceptanceRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-acceptance", String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), request.role().name(),
                request.operatorId(), request.opinion());
        return idempotencyService.execute("maintenance-acceptance", request.requestId(), fingerprint, () -> {
            // 先锁线路再锁检修单，与上客/复开门禁保持一致的加锁次序
            MaintenanceOrder before = maintenanceRepository.findById(orderId)
                    .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
            lineRepository.findByIdForUpdate(before.lineId())
                    .orElseThrow(() -> ApiException.notFound("线路不存在: " + before.lineId()));
            MaintenanceOrder order = maintenanceRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
            requireScheduled(order);
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("检修单版本已过期: expectedVersion=" + request.expectedVersion()
                        + ", current=" + order.version());
            }
            Instant now = clock.now();
            if (now.isBefore(order.endAt())) {
                throw ApiException.unprocessable("验收不得早于检修窗口结束时刻: endAt=" + order.endAt());
            }
            List<Long> listed = maintenanceRepository.listSegmentIds(orderId);
            if (maintenanceRepository.countClearedSegments(orderId) < listed.size()) {
                throw ApiException.unprocessable("检修清单分段尚未全部清空，不能验收");
            }
            List<MaintenanceAcceptance> existing = maintenanceRepository.listAcceptances(orderId);
            MaintenanceAcceptanceRole role = request.role();
            boolean roleDone = existing.stream().anyMatch(a -> a.role() == role);
            if (roleDone) {
                throw ApiException.conflict("该岗位已完成验收: " + role);
            }
            boolean otherSamePerson = existing.stream()
                    .anyMatch(a -> a.role() != role && a.operatorId().equals(request.operatorId()));
            if (otherSamePerson) {
                throw ApiException.unprocessable("机务与检修验收须由两个不同人员分别完成");
            }
            maintenanceRepository.insertAcceptance(orderId, role, request.operatorId(),
                    request.opinion(), now);
            List<MaintenanceAcceptance> all = maintenanceRepository.listAcceptances(orderId);
            boolean mechanical = all.stream().anyMatch(a -> a.role() == MaintenanceAcceptanceRole.MECHANICAL);
            boolean repair = all.stream().anyMatch(a -> a.role() == MaintenanceAcceptanceRole.REPAIR);
            Instant completedAt = null;
            if (mechanical && repair) {
                int updated = maintenanceRepository.complete(orderId, request.expectedVersion(), now);
                if (updated == 0) {
                    throw ApiException.conflict("检修单版本已过期或状态已变化");
                }
                completedAt = now;
            }
            return json.ok(toAcceptanceResponse(orderId,
                    completedAt == null ? order.version() : order.version() + 1,
                    completedAt, mechanical, repair, all));
        });
    }

    /**
     * 检修单详情视图：窗口、清单、分段清空证据与两岗验收人员意见。
     */
    public MaintenanceOrderDetailView orderDetail(long orderId) {
        MaintenanceOrder order = maintenanceRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
        List<Long> segmentIds = maintenanceRepository.listSegmentIds(orderId);
        List<MaintenanceClearance> clearances = maintenanceRepository.listClearances(orderId);
        List<MaintenanceAcceptance> acceptances = maintenanceRepository.listAcceptances(orderId);
        Instant now = clock.now();
        return new MaintenanceOrderDetailView(order.id(), order.lineId(), order.version(),
                order.status().name(), order.activeAt(now),
                order.startAt(), order.endAt(), order.reason(),
                order.completedAt(), order.cancelledAt(),
                segmentIds, clearances.size(), segmentIds.size(),
                clearances.stream().map(c -> new MaintenanceOrderDetailView.ClearanceView(
                        c.segmentId(), c.operatorId(), c.opinion(), c.createdAt())).toList(),
                acceptances.stream().map(a -> new MaintenanceOrderDetailView.AcceptanceView(
                        a.role().name(), a.operatorId(), a.opinion(), a.createdAt())).toList());
    }

    /**
     * 线路未终态检修单（只读视图用，按起点升序）。
     */
    public List<MaintenanceOrder> scheduledOrders(long lineId) {
        return maintenanceRepository.findScheduledByLine(lineId);
    }

    /**
     * 在已持有线路行锁的事务内，对线路未终态检修单加锁并返回当前时刻生效的封锁。
     */
    public List<MaintenanceOrder> activeOrdersForUpdate(long lineId, Instant now) {
        return maintenanceRepository.findScheduledByLineForUpdate(lineId).stream()
                .filter(order -> order.activeAt(now))
                .toList();
    }

    private WindLine lockLine(long lineId) {
        return lineRepository.findByIdForUpdate(lineId)
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
    }

    private MaintenanceOrder lockOrderForWrite(long orderId) {
        MaintenanceOrder order = maintenanceRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
        lineRepository.findByIdForUpdate(order.lineId())
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + order.lineId()));
        return maintenanceRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
    }

    private void requireScheduled(MaintenanceOrder order) {
        if (order.status() != MaintenanceOrderStatus.SCHEDULED) {
            throw ApiException.conflict("检修单已终态，不允许该操作: " + order.status());
        }
    }

    private void validateWindow(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw ApiException.unprocessable("检修窗口结束时刻必须晚于开始时刻");
        }
    }

    /**
     * 校验清单非空、分段存在且全部属于该线路，去重后按分段顺序号升序返回。
     */
    private List<Long> validateSegments(long lineId, List<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.unprocessable("检修分段清单不能为空");
        }
        List<Long> distinct = requested.stream().distinct().toList();
        if (distinct.size() != requested.size()) {
            throw ApiException.unprocessable("检修分段清单存在重复分段");
        }
        List<Long> segments = distinct.stream().map(segmentId -> {
            WindSegment segment = segmentRepository.findById(segmentId)
                    .orElseThrow(() -> ApiException.notFound("分段不存在: " + segmentId));
            if (segment.lineId() != lineId) {
                throw ApiException.unprocessable("分段不属于检修单绑定线路: segmentId=" + segmentId);
            }
            return segment;
        }).sorted(Comparator.comparingInt(WindSegment::seqNo)).map(WindSegment::id).toList();
        return segments;
    }

    private String canonicalSegments(List<Long> segmentIds) {
        return segmentIds.stream().distinct().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private String canonicalSegments(MaintenanceOrderRequest request) {
        return canonicalSegments(request.segmentIds());
    }

    private MaintenanceOrderResponse toOrderResponse(MaintenanceOrder order, List<Long> segmentIds) {
        return new MaintenanceOrderResponse(order.id(), order.lineId(), order.version(),
                order.status().name(), order.startAt(), order.endAt(), order.reason(), segmentIds);
    }

    private MaintenanceAcceptanceResponse toAcceptanceResponse(long orderId, int version, Instant completedAt,
                                                               boolean mechanical, boolean repair,
                                                               List<MaintenanceAcceptance> all) {
        List<MaintenanceAcceptanceResponse.AcceptanceView> views = all.stream()
                .map(a -> new MaintenanceAcceptanceResponse.AcceptanceView(
                        a.role().name(), a.operatorId(), a.opinion(), a.createdAt()))
                .toList();
        return new MaintenanceAcceptanceResponse(orderId, version,
                completedAt == null ? MaintenanceOrderStatus.SCHEDULED.name()
                        : MaintenanceOrderStatus.COMPLETED.name(),
                mechanical, repair, completedAt != null, completedAt, views);
    }
}
