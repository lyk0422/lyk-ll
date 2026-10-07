package com.example.starter.service;

import com.example.starter.model.AcceptanceRole;
import com.example.starter.model.MaintenanceAcceptance;
import com.example.starter.model.MaintenanceOrder;
import com.example.starter.model.MaintenanceOrderStatus;
import com.example.starter.model.MaintenanceSegmentClearance;
import com.example.starter.model.WindSegment;
import com.example.starter.repo.LineRepository;
import com.example.starter.repo.MaintenanceRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceCancelRequest;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceOrderCreateRequest;
import com.example.starter.web.dto.MaintenanceOrderView;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检修封锁窗口业务：登记、取消、分段清空与双岗验收。
 * 生效（到达窗口起点）即阻断整条线路上客与复开，到窗口终点未验收仍阻断；
 * 所有写操作与幂等记录同事务，并按线路行锁与上客、复开串行化。
 */
@Service
public class MaintenanceService {

    private final LineRepository lineRepository;
    private final SegmentRepository segmentRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;
    private final JsonSupport json;

    public MaintenanceService(LineRepository lineRepository, SegmentRepository segmentRepository,
                              MaintenanceRepository maintenanceRepository,
                              IdempotencyService idempotencyService, TimeProvider timeProvider,
                              JsonSupport json) {
        this.lineRepository = lineRepository;
        this.segmentRepository = segmentRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
        this.json = json;
    }

    /**
     * 登记检修单：清单非空且同属本线路，窗口 [start,end) 内部重叠拒绝、端点相接允许。
     */
    public ResponseEntity<String> createOrder(long lineId, MaintenanceOrderCreateRequest request) {
        List<Long> segmentIds = request.segmentIds() == null ? List.of()
                : List.copyOf(new TreeSet<>(request.segmentIds()));
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-create", String.valueOf(lineId),
                segmentIds.stream().map(String::valueOf).collect(Collectors.joining(",")),
                request.startAt().toString(), request.endAt().toString());
        return idempotencyService.execute("maintenance-create", request.requestId(), fingerprint, () -> {
            lineRepository.findByIdForUpdate(lineId)
                    .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
            validateWindow(lineId, request);
            long orderId = maintenanceRepository.insertOrder(lineId, request.startAt(), request.endAt());
            for (Long segmentId : new TreeSet<>(request.segmentIds())) {
                maintenanceRepository.insertOrderSegment(orderId, segmentId);
            }
            return json.ok(view(maintenanceRepository.findOrderById(orderId).orElseThrow()));
        });
    }

    /**
     * 取消检修单：仅未生效（当前时刻早于窗口起点）的已登记单可取消。
     */
    public ResponseEntity<String> cancel(long orderId, MaintenanceCancelRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-cancel", String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), request.operatorId());
        return idempotencyService.execute("maintenance-cancel", request.requestId(), fingerprint, () -> {
            MaintenanceOrder order = lockOrder(orderId);
            requireVersion(order, request.expectedVersion());
            if (order.status() != MaintenanceOrderStatus.SCHEDULED) {
                throw ApiException.conflict("检修单当前状态不允许取消: " + order.status());
            }
            Instant now = timeProvider.now();
            if (!now.isBefore(order.startAt())) {
                throw ApiException.conflict("检修单已生效，不允许取消");
            }
            maintenanceRepository.cancel(orderId, request.expectedVersion(), now);
            return json.ok(view(maintenanceRepository.findOrderById(orderId).orElseThrow()));
        });
    }

    /**
     * 登记分段清空证据：生效后逐段登记，分段须在工单清单内，每段仅一条。
     */
    public ResponseEntity<String> recordClearance(long orderId, MaintenanceClearanceRequest request) {
        String note = request.note() == null ? "" : request.note();
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-clearance", String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), String.valueOf(request.segmentId()),
                request.operatorId(), note);
        return idempotencyService.execute("maintenance-clearance", request.requestId(), fingerprint, () -> {
            MaintenanceOrder order = lockOrder(orderId);
            requireVersion(order, request.expectedVersion());
            if (order.status() != MaintenanceOrderStatus.SCHEDULED) {
                throw ApiException.conflict("检修单当前状态不允许登记清空: " + order.status());
            }
            Instant now = timeProvider.now();
            if (now.isBefore(order.startAt())) {
                throw ApiException.conflict("检修单未生效，不允许登记清空");
            }
            if (!maintenanceRepository.listOrderSegments(orderId).contains(request.segmentId())) {
                throw ApiException.unprocessable("分段不在检修单清单内: segmentId=" + request.segmentId());
            }
            if (!maintenanceRepository.insertClearance(orderId, request.segmentId(),
                    request.operatorId(), request.note())) {
                throw ApiException.conflict("分段已登记清空: segmentId=" + request.segmentId());
            }
            maintenanceRepository.bumpVersion(orderId, request.expectedVersion());
            return json.ok(view(maintenanceRepository.findOrderById(orderId).orElseThrow()));
        });
    }

    /**
     * 验收：不得早于窗口终点（恰终点可验收）；须各段清空且机务、检修两岗由不同人员分别完成。
     * 两岗齐全时工单完成，仅解除检修自身阻断。
     */
    public ResponseEntity<String> accept(long orderId, MaintenanceAcceptanceRequest request) {
        String opinion = request.opinion() == null ? "" : request.opinion();
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "maintenance-acceptance", String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), request.role().name(),
                request.operatorId(), opinion);
        return idempotencyService.execute("maintenance-acceptance", request.requestId(), fingerprint, () -> {
            MaintenanceOrder order = lockOrder(orderId);
            requireVersion(order, request.expectedVersion());
            if (order.status() != MaintenanceOrderStatus.SCHEDULED) {
                throw ApiException.conflict("检修单当前状态不允许验收: " + order.status());
            }
            Instant now = timeProvider.now();
            if (now.isBefore(order.endAt())) {
                throw ApiException.unprocessable("验收不得早于窗口终点: " + order.endAt());
            }
            List<Long> segments = maintenanceRepository.listOrderSegments(orderId);
            List<MaintenanceSegmentClearance> clearances = maintenanceRepository.listClearances(orderId);
            if (clearances.size() < segments.size()) {
                throw ApiException.unprocessable("检修分段尚未全部清空");
            }
            List<MaintenanceAcceptance> acceptances = maintenanceRepository.listAcceptances(orderId);
            boolean samePerson = acceptances.stream()
                    .anyMatch(a -> a.operatorId().equals(request.operatorId()));
            if (samePerson) {
                throw ApiException.unprocessable("机务与检修验收须由两个不同人员完成");
            }
            if (!maintenanceRepository.insertAcceptance(orderId, request.role(),
                    request.operatorId(), request.opinion())) {
                throw ApiException.conflict("该岗位已验收: " + request.role());
            }
            boolean completed = maintenanceRepository.listAcceptances(orderId).size() >= 2;
            if (completed) {
                maintenanceRepository.complete(orderId, request.expectedVersion(), now);
            } else {
                maintenanceRepository.bumpVersion(orderId, request.expectedVersion());
            }
            return json.ok(view(maintenanceRepository.findOrderById(orderId).orElseThrow()));
        });
    }

    /**
     * 查询检修单视图（含分段清空证据与验收人员意见）。
     */
    public MaintenanceOrderView orderView(long orderId) {
        MaintenanceOrder order = maintenanceRepository.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
        return view(order);
    }

    private void validateWindow(long lineId, MaintenanceOrderCreateRequest request) {
        if (request.segmentIds() == null || request.segmentIds().isEmpty()) {
            throw ApiException.unprocessable("涉及分段清单不能为空");
        }
        if (new HashSet<>(request.segmentIds()).size() != request.segmentIds().size()) {
            throw ApiException.unprocessable("涉及分段清单存在重复分段");
        }
        if (!request.startAt().isBefore(request.endAt())) {
            throw ApiException.unprocessable("窗口起点必须早于终点");
        }
        Map<Long, WindSegment> byId = segmentRepository.findByLineId(lineId).stream()
                .collect(Collectors.toMap(WindSegment::id, Function.identity()));
        for (Long segmentId : request.segmentIds()) {
            if (!byId.containsKey(segmentId)) {
                throw ApiException.unprocessable("分段不存在或不属于本线路: segmentId=" + segmentId);
            }
        }
        if (maintenanceRepository.countOverlapping(lineId, request.startAt(), request.endAt()) > 0) {
            throw ApiException.conflict("同线路检修窗口内部重叠，拒绝登记");
        }
    }

    private MaintenanceOrder lockOrder(long orderId) {
        MaintenanceOrder order = maintenanceRepository.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("检修单不存在: " + orderId));
        // 先锁线路行，与上客、复开串行化；再锁工单行
        lineRepository.findByIdForUpdate(order.lineId())
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + order.lineId()));
        return maintenanceRepository.findOrderByIdForUpdate(orderId).orElseThrow();
    }

    private void requireVersion(MaintenanceOrder order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("检修单版本不一致: expected=" + expectedVersion
                    + ", actual=" + order.version());
        }
    }

    private MaintenanceOrderView view(MaintenanceOrder order) {
        List<Long> segments = maintenanceRepository.listOrderSegments(order.id());
        Map<Long, MaintenanceSegmentClearance> clearanceBySegment = maintenanceRepository
                .listClearances(order.id()).stream()
                .collect(Collectors.toMap(MaintenanceSegmentClearance::segmentId, Function.identity()));
        List<MaintenanceOrderView.SegmentClearanceView> segmentViews = segments.stream()
                .map(segmentId -> {
                    MaintenanceSegmentClearance clearance = clearanceBySegment.get(segmentId);
                    return new MaintenanceOrderView.SegmentClearanceView(segmentId, clearance != null,
                            clearance == null ? null : clearance.operatorId(),
                            clearance == null ? null : clearance.clearedAt());
                })
                .toList();
        List<MaintenanceOrderView.AcceptanceView> acceptanceViews = maintenanceRepository
                .listAcceptances(order.id()).stream()
                .map(a -> new MaintenanceOrderView.AcceptanceView(
                        a.role().name(), a.operatorId(), a.opinion(), a.acceptedAt()))
                .toList();
        return new MaintenanceOrderView(order.id(), order.lineId(), order.status().name(), order.version(),
                order.startAt(), order.endAt(), order.blocksAt(timeProvider.now()),
                segmentViews, acceptanceViews, order.cancelledAt(), order.completedAt());
    }
}
