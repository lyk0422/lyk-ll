package com.example.starter.battery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.battery.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.battery.web.ConflictException;
import com.example.starter.battery.web.NotFoundException;
import com.example.starter.battery.web.Requests.InboundRequest;
import com.example.starter.battery.web.Requests.InspectionRequest;
import com.example.starter.battery.web.Requests.RegisterBatteryRequest;
import com.example.starter.battery.web.Requests.SwapOutRequest;
import com.example.starter.battery.web.Requests.TransferRequest;
import com.example.starter.battery.web.Responses.BatteryResponse;
import com.example.starter.battery.web.Responses.InboundResponse;
import com.example.starter.battery.web.Responses.InspectionResponse;
import com.example.starter.battery.web.Responses.SwapOutResponse;
import com.example.starter.battery.web.Responses.TransferResponse;
import com.example.starter.battery.web.Responses.VerifyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 电池资产流转台账核心服务：建档、入站、检测、换出、调拨与事件链维护。
 */
@Service
public class BatteryLedgerService {

    /** 上架 SOC 门槛（百分比，含边界）。 */
    public static final int SOC_THRESHOLD = 90;
    /** 上架健康度门槛（百分比，含边界）。 */
    public static final int HEALTH_THRESHOLD = 80;
    /** 调拨在途超时时长（毫秒）：24 小时。 */
    public static final long TRANSFER_TIMEOUT_MILLIS = 24L * 60 * 60 * 1000;

    private static final Logger log = LoggerFactory.getLogger(BatteryLedgerService.class);
    private static final String PENDING_RESPONSE = "PENDING";

    private final BatteryRepository batteryRepo;
    private final BatteryEventRepository eventRepo;
    private final TransferRepository transferRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final AlarmRepository alarmRepo;
    private final TimeProvider time;
    private final ObjectMapper objectMapper;

    public BatteryLedgerService(BatteryRepository batteryRepo,
                                BatteryEventRepository eventRepo,
                                TransferRepository transferRepo,
                                IdempotencyRepository idempotencyRepo,
                                AlarmRepository alarmRepo,
                                TimeProvider time,
                                ObjectMapper objectMapper) {
        this.batteryRepo = batteryRepo;
        this.eventRepo = eventRepo;
        this.transferRepo = transferRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.alarmRepo = alarmRepo;
        this.time = time;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 建档
    // ------------------------------------------------------------------

    /** 建档：电池编码全局唯一，生成创世事件。 */
    @Transactional
    public BatteryResponse register(RegisterBatteryRequest req) {
        long now = time.nowMillis();
        try {
            batteryRepo.insert(req.code(), BatteryStatus.PENDING_INSPECTION, req.stationId(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("电池编码已存在: " + req.code());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", req.code());
        payload.put("stationId", req.stationId());
        appendEvent(req.code(), EventType.REGISTER, payload);
        return BatteryResponse.from(mustFind(req.code()));
    }

    // ------------------------------------------------------------------
    // 入站（幂等）
    // ------------------------------------------------------------------

    /** 入站：校验编码与历史链一致，仅允许车辆侧电池回站。 */
    @Transactional
    public InboundResponse inbound(InboundRequest req) {
        return executeIdempotent(req.requestId(), "INBOUND",
                fingerprint("INBOUND", req.requestId(), req.code(), req.stationId()),
                InboundResponse.class, () -> doInbound(req));
    }

    private InboundResponse doInbound(InboundRequest req) {
        Battery battery = mustFind(req.code());
        if (!verifyChain(req.code())) {
            alarmRepo.insert(req.code(), "入站校验事件链不一致，拒绝入站", time.nowMillis());
            log.warn("电池 {} 事件链校验不一致，拒绝入站并告警", req.code());
            throw new ConflictException("电池历史链校验不一致，已告警并拒绝入站");
        }
        int updated = batteryRepo.inboundFromVehicle(req.code(), req.stationId(), time.nowMillis());
        if (updated == 0) {
            throw new ConflictException("电池当前状态不允许入站: " + battery.status());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stationId", req.stationId());
        BatteryEvent event = appendEvent(req.code(), EventType.INBOUND, payload);
        return new InboundResponse(req.code(), BatteryStatus.PENDING_INSPECTION.name(),
                req.stationId(), event.seq());
    }

    // ------------------------------------------------------------------
    // 检测（幂等，结论不可修改，复检走新批次）
    // ------------------------------------------------------------------

    /** 检测：SOC>=90 且健康度>=80 方可上架，否则进入充电/待修。 */
    @Transactional
    public InspectionResponse inspect(InspectionRequest req) {
        return executeIdempotent(req.requestId(), "INSPECTION",
                fingerprint("INSPECTION", req.requestId(), req.code(),
                        String.valueOf(req.batch()), String.valueOf(req.soc()),
                        String.valueOf(req.health())),
                InspectionResponse.class, () -> doInspect(req));
    }

    private InspectionResponse doInspect(InspectionRequest req) {
        Battery battery = mustFind(req.code());
        if (battery.inspectionBatch() != null && battery.inspectionBatch() >= req.batch()) {
            throw new ConflictException("检测结论不可修改，复检须使用更大的新批次号");
        }
        boolean passed = req.soc() >= SOC_THRESHOLD && req.health() >= HEALTH_THRESHOLD;
        BatteryStatus newStatus = passed ? BatteryStatus.LISTED : BatteryStatus.CHARGING;
        int updated = batteryRepo.applyInspection(req.code(), req.soc(), req.health(),
                req.batch(), newStatus, time.nowMillis());
        if (updated == 0) {
            throw new ConflictException("电池当前状态不允许检测: " + battery.status());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch", req.batch());
        payload.put("soc", req.soc());
        payload.put("health", req.health());
        payload.put("passed", passed);
        appendEvent(req.code(), EventType.INSPECTION, payload);
        if (passed) {
            Map<String, Object> listedPayload = new LinkedHashMap<>();
            listedPayload.put("stationId", battery.stationId());
            appendEvent(req.code(), EventType.LISTED, listedPayload);
        }
        return new InspectionResponse(req.code(), req.batch(), req.soc(), req.health(),
                passed, newStatus.name());
    }

    // ------------------------------------------------------------------
    // 换出（幂等，并发唯一成功）
    // ------------------------------------------------------------------

    /** 换出：从站点库存扣减并绑定车辆，同一电池同一时刻仅允许一次换出。 */
    @Transactional
    public SwapOutResponse swapOut(SwapOutRequest req) {
        return executeIdempotent(req.requestId(), "SWAP_OUT",
                fingerprint("SWAP_OUT", req.requestId(), req.code(), req.vehicleId()),
                SwapOutResponse.class, () -> doSwapOut(req));
    }

    private SwapOutResponse doSwapOut(SwapOutRequest req) {
        Battery battery = mustFind(req.code());
        int updated = batteryRepo.swapOutIfListed(req.code(), req.vehicleId(), time.nowMillis());
        if (updated == 0) {
            throw new ConflictException("电池不可换出，当前状态: " + battery.status());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vehicleId", req.vehicleId());
        payload.put("fromStation", battery.stationId());
        BatteryEvent event = appendEvent(req.code(), EventType.SWAP_OUT, payload);
        return new SwapOutResponse(req.code(), req.vehicleId(),
                BatteryStatus.IN_USE.name(), event.seq());
    }

    // ------------------------------------------------------------------
    // 站间调拨（发起幂等、签收幂等、超时异常、核销唯一）
    // ------------------------------------------------------------------

    /** 调拨发起：调出方发起，在途期间禁止换出与再次调拨。 */
    @Transactional
    public TransferResponse initiateTransfer(TransferRequest req) {
        return executeIdempotent(req.requestId(), "TRANSFER",
                fingerprint("TRANSFER", req.requestId(), req.code(),
                        req.fromStationId(), req.toStationId()),
                TransferResponse.class, () -> doInitiateTransfer(req));
    }

    private TransferResponse doInitiateTransfer(TransferRequest req) {
        Battery battery = mustFind(req.code());
        if (req.fromStationId().equals(req.toStationId())) {
            throw new ConflictException("调出站点与调入站点不能相同");
        }
        if (!req.fromStationId().equals(battery.stationId())) {
            throw new ConflictException("调出方与电池当前站点不一致");
        }
        int updated = batteryRepo.markInTransitIfListed(req.code(), time.nowMillis());
        if (updated == 0) {
            throw new ConflictException("电池当前状态不允许调拨: " + battery.status());
        }
        long transferId = transferRepo.insert(req.code(), req.fromStationId(),
                req.toStationId(), time.nowMillis());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transferId);
        payload.put("fromStation", req.fromStationId());
        payload.put("toStation", req.toStationId());
        appendEvent(req.code(), EventType.TRANSFER_OUT, payload);
        return TransferResponse.from(mustFindTransfer(transferId));
    }

    /** 调拨签收：调入方签收，在途单方可签收。 */
    @Transactional
    public TransferResponse signTransfer(long transferId, String requestId) {
        return executeIdempotent(requestId, "TRANSFER_SIGN",
                fingerprint("TRANSFER_SIGN", requestId, String.valueOf(transferId)),
                TransferResponse.class, () -> doSignTransfer(transferId));
    }

    private TransferResponse doSignTransfer(long transferId) {
        Transfer transfer = mustFindTransfer(transferId);
        refreshExceptionIfOverdue(transfer);
        int updated = transferRepo.signIfInTransit(transferId, time.nowMillis());
        if (updated == 0) {
            throw new ConflictException("调拨单当前状态不允许签收: " + transfer.status());
        }
        batteryRepo.signInTransit(transfer.batteryCode(), transfer.toStation(), time.nowMillis());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transferId);
        payload.put("toStation", transfer.toStation());
        appendEvent(transfer.batteryCode(), EventType.TRANSFER_SIGN, payload);
        return TransferResponse.from(mustFindTransfer(transferId));
    }

    /** 异常核销：仅调拨异常单可核销且仅一次，核销后电池回调出站待检。 */
    @Transactional
    public TransferResponse writeOffTransfer(long transferId) {
        Transfer transfer = mustFindTransfer(transferId);
        refreshExceptionIfOverdue(transfer);
        int updated = transferRepo.writeOffIfException(transferId, time.nowMillis());
        if (updated == 0) {
            throw new ConflictException("调拨单当前状态不允许核销或已核销: " + transfer.status());
        }
        batteryRepo.writeOffException(transfer.batteryCode(), transfer.fromStation(),
                time.nowMillis());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transferId);
        appendEvent(transfer.batteryCode(), EventType.WRITE_OFF, payload);
        return TransferResponse.from(mustFindTransfer(transferId));
    }

    /**
     * 扫描在途调拨单，超过 24 小时（UTC）自动转调拨异常。
     *
     * @return 本次转异常的单数
     */
    @Transactional
    public int refreshOverdueTransfers() {
        int count = 0;
        for (Transfer transfer : transferRepo.findAllInTransit()) {
            if (refreshExceptionIfOverdue(transfer)) {
                count++;
            }
        }
        return count;
    }

    /** 若在途超过 24 小时则转异常，返回是否发生转换。 */
    private boolean refreshExceptionIfOverdue(Transfer transfer) {
        if (transfer.status() != TransferStatus.IN_TRANSIT) {
            return false;
        }
        long now = time.nowMillis();
        if (now - transfer.initiatedAt() <= TRANSFER_TIMEOUT_MILLIS) {
            return false;
        }
        int updated = transferRepo.markExceptionIfInTransit(transfer.id(), now);
        if (updated == 0) {
            return false;
        }
        batteryRepo.markExceptionIfInTransit(transfer.batteryCode(), now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.id());
        payload.put("overdueMillis", now - transfer.initiatedAt());
        appendEvent(transfer.batteryCode(), EventType.TRANSFER_EXCEPTION, payload);
        log.warn("调拨单 {} 在途超过 24 小时，自动转调拨异常", transfer.id());
        return true;
    }

    // ------------------------------------------------------------------
    // 查询与验真
    // ------------------------------------------------------------------

    /** 查询电池台账。 */
    @Transactional(readOnly = true)
    public BatteryResponse getBattery(String code) {
        return BatteryResponse.from(mustFind(code));
    }

    /** 查询电池完整事件链。 */
    @Transactional(readOnly = true)
    public List<BatteryEvent> getEvents(String code) {
        mustFind(code);
        return eventRepo.findChain(code);
    }

    /** 查询调拨单（查询前刷新超时状态）。 */
    @Transactional
    public TransferResponse getTransfer(long transferId) {
        Transfer transfer = mustFindTransfer(transferId);
        refreshExceptionIfOverdue(transfer);
        return TransferResponse.from(mustFindTransfer(transferId));
    }

    /**
     * 校验事件链完整性：序号连续、前哈希衔接、内容哈希一致。
     */
    public boolean verifyChain(String code) {
        List<BatteryEvent> events = eventRepo.findChain(code);
        String expectedPrev = Hashes.GENESIS_PREV_HASH;
        long expectedSeq = 1;
        for (BatteryEvent event : events) {
            if (event.seq() != expectedSeq || !event.prevHash().equals(expectedPrev)
                    || !computeHash(event).equals(event.hash())) {
                return false;
            }
            expectedPrev = event.hash();
            expectedSeq++;
        }
        return true;
    }

    /** 验真接口。 */
    @Transactional(readOnly = true)
    public VerifyResponse verify(String code) {
        mustFind(code);
        List<BatteryEvent> events = eventRepo.findChain(code);
        return new VerifyResponse(code, events.size(), verifyChain(code));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 追加事件到链尾并计算哈希。 */
    private BatteryEvent appendEvent(String code, EventType type, Map<String, Object> payload) {
        String payloadJson = toJson(payload);
        BatteryEvent latest = eventRepo.findLatest(code).orElse(null);
        long seq = latest == null ? 1 : latest.seq() + 1;
        String prevHash = latest == null ? Hashes.GENESIS_PREV_HASH : latest.hash();
        long now = time.nowMillis();
        String hash = Hashes.sha256(hashMaterial(code, seq, type.name(), payloadJson, prevHash, now));
        BatteryEvent event = new BatteryEvent(0, code, seq, type, payloadJson, prevHash, hash, now);
        eventRepo.append(event);
        return event;
    }

    /** 重算事件哈希（用于验真）。 */
    private static String computeHash(BatteryEvent event) {
        return Hashes.sha256(hashMaterial(event.batteryCode(), event.seq(),
                event.eventType().name(), event.payload(), event.prevHash(), event.createdAt()));
    }

    private static String hashMaterial(String code, long seq, String type,
                                       String payload, String prevHash, long createdAt) {
        return code + "|" + seq + "|" + type + "|" + payload + "|" + prevHash + "|" + createdAt;
    }

    /** 幂等指纹：覆盖 requestId 与全部业务字段。 */
    private static String fingerprint(String operation, String... fields) {
        return Hashes.sha256(operation + "|" + String.join("|", fields));
    }

    /**
     * 幂等执行：先占位记录，同键同参返回首次结果，同键异参返回 409 且不产生新事件；
     * 业务失败时删除占位，允许修正后重试。
     */
    private <T> T executeIdempotent(String requestId, String operation, String fingerprint,
                                    Class<T> responseType, Supplier<T> action) {
        boolean first = idempotencyRepo.tryInsert(requestId, operation, fingerprint,
                PENDING_RESPONSE, time.nowMillis());
        if (!first) {
            IdempotencyRecord record = idempotencyRepo.findByRequestId(requestId)
                    .orElseThrow(() -> new IllegalStateException("幂等记录读取失败: " + requestId));
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已存在且业务参数不一致，拒绝重复提交");
            }
            if (PENDING_RESPONSE.equals(record.response())) {
                throw new ConflictException("相同请求正在处理中，请稍后重试");
            }
            return fromJson(record.response(), responseType);
        }
        try {
            T result = action.get();
            idempotencyRepo.updateResponse(requestId, toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotencyRepo.deleteByRequestId(requestId);
            throw e;
        }
    }

    private Battery mustFind(String code) {
        return batteryRepo.findByCode(code)
                .orElseThrow(() -> new NotFoundException("电池不存在: " + code));
    }

    private Transfer mustFindTransfer(long transferId) {
        return transferRepo.findById(transferId)
                .orElseThrow(() -> new NotFoundException("调拨单不存在: " + transferId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
