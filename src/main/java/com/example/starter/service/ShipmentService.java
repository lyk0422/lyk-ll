package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.VehicleStatus;
import com.example.starter.error.ApiException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.BatchRepository.BatchRow;
import com.example.starter.repo.ShipmentRepository;
import com.example.starter.repo.ShipmentRepository.ShipmentHeadRow;
import com.example.starter.repo.ShipmentRepository.VehicleRow;
import com.example.starter.web.dto.ShipmentProgressView;
import com.example.starter.web.dto.SplitEnableRequest;
import com.example.starter.web.dto.SplitEnableResponse;
import com.example.starter.web.dto.VehicleCreateRequest;
import com.example.starter.web.dto.VehicleDepartRequest;
import com.example.starter.web.dto.VehicleRevokeRequest;
import com.example.starter.web.dto.VehicleView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分车出厂服务：启用分车、车单全量预占、未发车撤销、逐车核销与按头只进度查询。
 * 原逐头同步检验、风险抽检门禁与无害化回执门禁保留，逐车核销事务内重查全部门禁。
 */
@Service
public class ShipmentService {

    private final BatchRepository batchRepo;
    private final ShipmentRepository shipmentRepo;
    private final RiskService riskService;
    /** 复用原无害化回执处置门禁勾稽，逐车核销事务内重查。 */
    private final BatchService batchService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final IdempotentExecutor idempotency;

    public ShipmentService(BatchRepository batchRepo, ShipmentRepository shipmentRepo,
                           RiskService riskService, BatchService batchService, Clock clock,
                           ObjectMapper objectMapper, IdempotentExecutor idempotency) {
        this.batchRepo = batchRepo;
        this.shipmentRepo = shipmentRepo;
        this.riskService = riskService;
        this.batchService = batchService;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
    }

    /** 启用分车出厂（幂等）：有合格头只且尚未出厂的批次可启用。 */
    @Transactional
    public SplitEnableResponse enableSplit(String batchNo, SplitEnableRequest req) {
        parseOccurredAt(req.occurredAt());
        return idempotency.run("split-enable", req.requestId(), req, SplitEnableResponse.class,
                () -> doEnableSplit(batchNo));
    }

    private SplitEnableResponse doEnableSplit(String batchNo) {
        BatchRow batch = lockAndReload(batchNo);
        int qualified = batchRepo.countQualifiedHeads(batch.id());
        // 已启用：幂等返回当前状态（不要求仍有未出厂头只，终态后查询同样稳定）。
        if (batch.splitEnabled()) {
            return new SplitEnableResponse(batchNo, batch.status(), true, qualified);
        }
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已最终出厂，不允许再启用分车");
        }
        if (qualified <= 0) {
            throw ApiException.conflict("批次尚无合格头只，不允许启用分车出厂");
        }
        int updated = batchRepo.enableSplit(batch.id());
        if (updated == 0) {
            throw ApiException.conflict("并发冲突：分车启用状态已变化");
        }
        batch = mustFindBatch(batchNo);
        return new SplitEnableResponse(batchNo, batch.status(), true, qualified);
    }

    /** 创建车辆清单并全量预占（幂等）：缺一项全单不占，创建后冻结。 */
    @Transactional
    public VehicleView createVehicle(String batchNo, VehicleCreateRequest req) {
        parseOccurredAt(req.occurredAt());
        return idempotency.run("vehicle-create", req.requestId(), req, VehicleView.class,
                () -> doCreateVehicle(batchNo, req));
    }

    private VehicleView doCreateVehicle(String batchNo, VehicleCreateRequest req) {
        BatchRow batch = lockAndReload(batchNo);
        if (!batch.splitEnabled()) {
            throw ApiException.conflict("批次尚未启用分车出厂，不允许建立车辆清单");
        }
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已最终出厂，不允许再建立车辆清单");
        }
        if (req.expectedVersion() != 0) {
            throw ApiException.conflict("新建车辆清单 expectedVersion 须为 0，成功后车单版本为 1");
        }
        if (shipmentRepo.findVehicleByNo(batch.id(), req.vehicleNo()).isPresent()) {
            throw ApiException.conflict("合成车号已存在：" + req.vehicleNo() + "，车单创建后不可改单");
        }
        List<String> frozen = normalizeHeads(req.headNos());
        if (frozen.size() > 50) {
            throw ApiException.badRequest("每车头只数须在 1 至 50 个之间");
        }
        // 出厂合格头只集合取原判定明细：跨批/病害处置头只一律拒绝。
        Set<String> qualified = new HashSet<>(batchRepo.findQualifiedHeadNos(batch.id()));
        List<String> notQualified = frozen.stream().filter(h -> !qualified.contains(h)).toList();
        if (!notQualified.isEmpty()) {
            throw ApiException.unprocessable("头只不属于本批合格集合（含跨批或病害处置头只），拒绝建单："
                    + notQualified);
        }
        // 批次行锁已在建单事务间串行；再显式核对占用，给出明确冲突信息。
        Set<String> occupied = new HashSet<>();
        for (ShipmentHeadRow row : shipmentRepo.findShipmentHeads(batch.id())) {
            occupied.add(row.headNo());
        }
        List<String> alreadyOccupied = frozen.stream().filter(occupied::contains).toList();
        if (!alreadyOccupied.isEmpty()) {
            throw ApiException.conflict("头只已被其他未撤销车辆占用，同头只争车只一方成功："
                    + alreadyOccupied);
        }
        String now = now();
        long vehicleId;
        try {
            vehicleId = shipmentRepo.insertVehicle(batch.id(), req.vehicleNo(), frozen.size(),
                    toJson(frozen), now);
            for (String headNo : frozen) {
                shipmentRepo.insertShipmentHead(batch.id(), vehicleId, headNo, now);
            }
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底（车号冲突或头只争车）：整单回滚，缺一项全单不占。
            throw ApiException.conflict("车辆清单预占冲突：车号重复或头只已被其他未撤销车辆占用");
        }
        VehicleRow vehicle = shipmentRepo.findVehicleByNo(batch.id(), req.vehicleNo()).orElseThrow();
        return toView(batchNo, vehicle, batch.status());
    }

    /** 撤销未发车清单并释放全部预占（幂等），不能事后改单。 */
    @Transactional
    public VehicleView revokeVehicle(String batchNo, String vehicleNo, VehicleRevokeRequest req) {
        parseOccurredAt(req.occurredAt());
        return idempotency.run("vehicle-revoke", req.requestId(), req, VehicleView.class,
                () -> doRevokeVehicle(batchNo, vehicleNo, req));
    }

    private VehicleView doRevokeVehicle(String batchNo, String vehicleNo, VehicleRevokeRequest req) {
        BatchRow batch = lockAndReload(batchNo);
        VehicleRow vehicle = shipmentRepo.findVehicleByNoForUpdate(batch.id(), vehicleNo)
                .orElseThrow(() -> ApiException.notFound("车辆清单不存在：" + vehicleNo));
        if (VehicleStatus.DEPARTED.name().equals(vehicle.status())) {
            throw ApiException.conflict("车辆已发车出厂，车单冻结不可撤销：" + vehicleNo);
        }
        if (VehicleStatus.REVOKED.name().equals(vehicle.status())) {
            throw ApiException.conflict("车辆清单已撤销，终态不可重复撤销：" + vehicleNo);
        }
        if (req.expectedVersion() != vehicle.version()) {
            throw ApiException.conflict("车单版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前版本=" + vehicle.version());
        }
        String now = now();
        int updated = shipmentRepo.transitionVehicle(vehicle.id(),
                VehicleStatus.RESERVED.name(), vehicle.version(),
                VehicleStatus.REVOKED.name(), vehicle.version() + 1, "revoked_at", now);
        if (updated == 0) {
            throw ApiException.conflict("并发冲突：车辆清单状态或版本已变化，撤销失败");
        }
        // 释放全部预占：删除预占行后这些头只可被其他车重新预占。
        shipmentRepo.deleteShipmentHeads(batch.id(), vehicle.id());
        VehicleRow revoked = shipmentRepo.findVehicleByNo(batch.id(), vehicleNo).orElseThrow();
        return toView(batchNo, revoked, batch.status());
    }

    /** 逐车核销（幂等）：事务内重查抽检/处置门禁、车单与版本，全部满足才整车转已出厂。 */
    @Transactional
    public VehicleView departVehicle(String batchNo, String vehicleNo, VehicleDepartRequest req) {
        parseOccurredAt(req.occurredAt());
        return idempotency.run("vehicle-depart", req.requestId(), req, VehicleView.class,
                () -> doDepartVehicle(batchNo, vehicleNo, req));
    }

    private VehicleView doDepartVehicle(String batchNo, String vehicleNo, VehicleDepartRequest req) {
        BatchRow batch = lockAndReload(batchNo);
        if (!batch.splitEnabled()) {
            throw ApiException.conflict("批次未启用分车，不能走逐车核销入口");
        }
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已最终出厂，全部合格头只已核销完");
        }
        if (BatchStatus.SPECIAL_QUARANTINE.name().equals(batch.status())) {
            throw ApiException.conflict("批次处于专项隔离，受控逐车出厂拒绝");
        }
        VehicleRow vehicle = shipmentRepo.findVehicleByNoForUpdate(batch.id(), vehicleNo)
                .orElseThrow(() -> ApiException.notFound("车辆清单不存在：" + vehicleNo));
        if (VehicleStatus.REVOKED.name().equals(vehicle.status())) {
            throw ApiException.conflict("车辆清单已撤销释放，不能发车：" + vehicleNo);
        }
        if (VehicleStatus.DEPARTED.name().equals(vehicle.status())) {
            throw ApiException.conflict("车辆已发车出厂，车单冻结不可重复核销：" + vehicleNo);
        }
        if (req.expectedVersion() != vehicle.version()) {
            throw ApiException.conflict("车单版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前版本=" + vehicle.version());
        }
        // 事务内重查原全批门禁：无害化回执勾稽 + 风险抽检（受控批全部清单完成且全阴）。
        batchService.assertReceiptReconciled(batch.id());
        riskService.assertReleaseGate(batch.id());

        String now = now();
        // 单车不得部分核销：本车全部 RESERVED 头只一次性转已出厂，受影响行数须恰等于冻结数。
        int marked = shipmentRepo.markHeadsDeparted(batch.id(), vehicle.id(), now);
        if (marked != vehicle.headCount()) {
            throw ApiException.conflict("车辆预占头只数与清单不一致（" + marked + "/"
                    + vehicle.headCount() + "），禁止部分核销，保留原预占");
        }
        int updated = shipmentRepo.transitionVehicle(vehicle.id(),
                VehicleStatus.RESERVED.name(), vehicle.version(),
                VehicleStatus.DEPARTED.name(), vehicle.version() + 1, "departed_at", now);
        if (updated == 0) {
            throw ApiException.conflict("并发冲突：车辆清单状态或版本已变化，核销失败保留原预占");
        }

        // 末车收口：全部合格头只恰核销完（按头只勾稽，不把病害头只算进未出厂）才转最终出厂。
        int qualifiedTotal = batchRepo.countQualifiedHeads(batch.id());
        long departedTotal = shipmentRepo.findShipmentHeads(batch.id()).stream()
                .filter(h -> "DEPARTED".equals(h.status()))
                .count();
        String finalBatchStatus = batch.status();
        if (departedTotal == qualifiedTotal) {
            int released = batchRepo.markReleased(batch.id(), now);
            if (released == 0) {
                throw ApiException.conflict("并发冲突：批次终态流转失败，本次车单核销与批次收口保持同事务");
            }
            finalBatchStatus = BatchStatus.RELEASED.name();
        } else if (departedTotal > qualifiedTotal) {
            throw ApiException.conflict("已出厂合格头只数超过合格判定数，勾稽异常："
                    + departedTotal + "/" + qualifiedTotal);
        }
        VehicleRow departed = shipmentRepo.findVehicleByNo(batch.id(), vehicleNo).orElseThrow();
        return toView(batchNo, departed, finalBatchStatus);
    }

    /** 分车进度查询：每车冻结清单、已出厂/未出厂头只及批次进度，按头只勾稽。 */
    @Transactional(readOnly = true)
    public ShipmentProgressView getProgress(String batchNo) {
        BatchRow batch = mustFindBatch(batchNo);
        List<String> qualified = batchRepo.findQualifiedHeadNos(batch.id());
        List<VehicleRow> vehicleRows = shipmentRepo.findVehicles(batch.id());
        List<ShipmentHeadRow> headRows = shipmentRepo.findShipmentHeads(batch.id());

        List<String> departed = new ArrayList<>();
        List<String> reserved = new ArrayList<>();
        for (ShipmentHeadRow row : headRows) {
            if ("DEPARTED".equals(row.status())) {
                departed.add(row.headNo());
            } else if ("RESERVED".equals(row.status())) {
                reserved.add(row.headNo());
            }
        }
        departed.sort(String::compareTo);
        reserved.sort(String::compareTo);
        // 未出厂 = 合格全集 − 已出厂；病害处置头只本就不在合格全集，故不会被算进未出厂人数。
        Set<String> departedSet = new HashSet<>(departed);
        List<String> unshipped = qualified.stream()
                .filter(h -> !departedSet.contains(h))
                .toList();

        List<VehicleView> vehicles = vehicleRows.stream()
                .map(v -> toView(batchNo, v, batch.status()))
                .toList();
        boolean allShipped = !qualified.isEmpty() && departed.size() == qualified.size();
        return new ShipmentProgressView(batchNo, batch.status(), batch.splitEnabled(),
                qualified.size(), List.copyOf(departed), List.copyOf(reserved),
                List.copyOf(unshipped), departed.size(), unshipped.size(), allShipped, vehicles);
    }

    // ---------- 内部：加锁与工具 ----------

    /** 锁定批次主行并重读最新状态，与风险变更/结果及整批出厂核销同序互斥。 */
    private BatchRow lockAndReload(String batchNo) {
        BatchRow batch = mustFindBatch(batchNo);
        batchRepo.lockBatch(batch.id());
        return batchRepo.findBatchByNo(batchNo).orElseThrow();
    }

    private BatchRow mustFindBatch(String batchNo) {
        return batchRepo.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + batchNo));
    }

    private String parseOccurredAt(String occurredAt) {
        try {
            return Instant.parse(occurredAt).toString();
        } catch (Exception e) {
            throw ApiException.badRequest("occurredAt 须为 UTC ISO-8601 时刻：" + occurredAt);
        }
    }

    /** 头只标识去重并按字典序排序；重复标识视为非法（车单须给出不同头只）。 */
    private List<String> normalizeHeads(List<String> headNos) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String headNo : headNos) {
            if (!distinct.add(headNo)) {
                throw ApiException.badRequest("车辆清单头只标识重复：" + headNo);
            }
        }
        List<String> sorted = new ArrayList<>(distinct);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    /** 由车单行组装冻结视图，批次终态与批次状态由调用方按操作后状态传入。 */
    private VehicleView toView(String batchNo, VehicleRow vehicle, String batchStatus) {
        boolean released = BatchStatus.RELEASED.name().equals(batchStatus);
        return new VehicleView(batchNo, vehicle.vehicleNo(), vehicle.version(), vehicle.status(),
                fromJson(vehicle.frozenHeads()), vehicle.headCount(), vehicle.createdAt(),
                vehicle.departedAt(), vehicle.revokedAt(), batchStatus, released);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("冻结清单序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("冻结清单反序列化失败", e);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
