package com.example.starter.ledger.service;

import com.example.starter.ledger.config.LedgerProperties;
import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.domain.EventType;
import com.example.starter.ledger.domain.TransferStatus;
import com.example.starter.ledger.po.BatteryPO;
import com.example.starter.ledger.po.ChainAlertPO;
import com.example.starter.ledger.po.IdempotentRecordPO;
import com.example.starter.ledger.po.LedgerEventPO;
import com.example.starter.ledger.po.TransferOrderPO;
import com.example.starter.ledger.repo.BatteryRepository;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.repo.IdempotentRepository;
import com.example.starter.ledger.repo.TransferRepository;
import com.example.starter.ledger.web.BatteryResponse;
import com.example.starter.ledger.web.EventResponse;
import com.example.starter.ledger.web.InboundRequest;
import com.example.starter.ledger.web.InspectionRequest;
import com.example.starter.ledger.web.OperationResult;
import com.example.starter.ledger.web.RegisterBatteryRequest;
import com.example.starter.ledger.web.SwapOutRequest;
import com.example.starter.ledger.web.TransferDispatchRequest;
import com.example.starter.ledger.web.TransferReceiveRequest;
import com.example.starter.ledger.web.TransferResponse;
import com.example.starter.ledger.web.TransferWriteOffRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 换电资产流转与门槛放行核心服务。
 *
 * <p>所有写操作在事务内先对电池行加 FOR UPDATE 行锁，串行化同一电池的并发操作；
 * 业务事件以“上一事件哈希”串联成 SHA-256 哈希链；requestId 幂等记录的指纹覆盖
 * requestId、操作类型与全部业务字段。
 */
@Service
public class LedgerService {

    private final BatteryRepository batteryRepository;
    private final EventRepository eventRepository;
    private final TransferRepository transferRepository;
    private final IdempotentRepository idempotentRepository;
    private final ChainVerifier chainVerifier;
    private final AlertService alertService;
    private final LedgerProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public LedgerService(BatteryRepository batteryRepository, EventRepository eventRepository,
            TransferRepository transferRepository, IdempotentRepository idempotentRepository,
            ChainVerifier chainVerifier, AlertService alertService, LedgerProperties properties,
            Clock clock, ObjectMapper objectMapper) {
        this.batteryRepository = batteryRepository;
        this.eventRepository = eventRepository;
        this.transferRepository = transferRepository;
        this.idempotentRepository = idempotentRepository;
        this.chainVerifier = chainVerifier;
        this.alertService = alertService;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 以唯一编码建档，写入链首 REGISTER 事件；编码重复返回 409。 */
    @Transactional
    public BatteryResponse registerBattery(RegisterBatteryRequest request) {
        Instant now = Instant.now(clock);
        BatteryPO battery = new BatteryPO();
        battery.setCode(request.getCode());
        battery.setStatus(BatteryStatus.REGISTERED.name());
        battery.setCurrentStation(null);
        battery.setBoundVehicle(null);
        battery.setHeadEventHash(null);
        battery.setVersion(0L);
        battery.setCreatedAt(now);
        battery.setUpdatedAt(now);
        try {
            batteryRepository.insert(battery);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("电池编码已存在: " + request.getCode());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batteryCode", battery.getCode());
        payload.put("eventType", EventType.REGISTER.name());
        payload.put("occurredAt", now.toString());
        battery.setHeadEventHash(appendEvent(battery, EventType.REGISTER, payload, null));
        battery.setUpdatedAt(now);
        batteryRepository.updateWithLock(battery);
        return toBatteryResponse(battery);
    }

    /** 回站入站；先校验历史链一致，不一致拒绝并告警。以 requestId 幂等。 */
    @Transactional
    public OperationResult<BatteryResponse> inbound(InboundRequest request) {
        BatteryPO battery = lockBatteryOr404(request.getBatteryCode());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batteryCode", request.getBatteryCode());
        fields.put("stationCode", request.getStationCode());
        IdempotentContext ctx = beginIdempotent("INBOUND", request.getRequestId(), fields);
        if (ctx.replayed != null) {
            return new OperationResult<>(ctx.readAs(BatteryResponse.class), true);
        }

        ChainVerifier.VerificationResult verification = chainVerifier.verify(battery.getCode());
        if (!verification.isValid()) {
            ChainAlertPO alert = new ChainAlertPO();
            alert.setBatteryCode(battery.getCode());
            alert.setStationCode(request.getStationCode());
            alert.setReason("入站链校验失败: " + verification.getReason());
            alert.setRequestId(request.getRequestId());
            alert.setCreatedAt(Instant.now(clock));
            // 告警须落库，即使业务事务因拒绝而回滚。
            alertService.recordInNewTransaction(alert);
            throw new ChainMismatchException("电池历史链不一致，拒绝入站: " + verification.getReason());
        }

        String status = battery.getStatus();
        if (!BatteryStatus.REGISTERED.name().equals(status) && !BatteryStatus.IN_USE.name().equals(status)) {
            throw new ConflictException("电池当前状态不允许入站: " + status);
        }
        Instant now = Instant.now(clock);
        battery.setStatus(BatteryStatus.AT_STATION.name());
        battery.setCurrentStation(request.getStationCode());
        battery.setBoundVehicle(null);
        battery.setUpdatedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batteryCode", battery.getCode());
        payload.put("eventType", EventType.INBOUND.name());
        payload.put("stationCode", request.getStationCode());
        payload.put("occurredAt", now.toString());
        battery.setHeadEventHash(appendEvent(battery, EventType.INBOUND, payload, request.getRequestId()));
        batteryRepository.updateWithLock(battery);
        BatteryResponse response = toBatteryResponse(battery);
        finishIdempotent(ctx, response);
        return new OperationResult<>(response, false);
    }

    /** 提交检测结论；SOC≥90 且健康度≥80（含恰好达标）才上架，否则进入充电/待修。结论不可修改，复检走新批次。 */
    @Transactional
    public OperationResult<BatteryResponse> inspect(InspectionRequest request) {
        BatteryPO battery = lockBatteryOr404(request.getBatteryCode());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batteryCode", request.getBatteryCode());
        fields.put("soc", request.getSoc());
        fields.put("health", request.getHealth());
        IdempotentContext ctx = beginIdempotent("INSPECTION", request.getRequestId(), fields);
        if (ctx.replayed != null) {
            return new OperationResult<>(ctx.readAs(BatteryResponse.class), true);
        }

        String status = battery.getStatus();
        if (!BatteryStatus.AT_STATION.name().equals(status) && !BatteryStatus.CHARGING.name().equals(status)) {
            throw new ConflictException("电池当前状态不允许检测: " + status);
        }
        boolean pass = request.getSoc() >= properties.getSocThreshold()
                && request.getHealth() >= properties.getHealthThreshold();
        Instant now = Instant.now(clock);

        Map<String, Object> inspectionPayload = new LinkedHashMap<>();
        inspectionPayload.put("batteryCode", battery.getCode());
        inspectionPayload.put("eventType", EventType.INSPECTION.name());
        inspectionPayload.put("soc", request.getSoc());
        inspectionPayload.put("health", request.getHealth());
        inspectionPayload.put("socThreshold", properties.getSocThreshold());
        inspectionPayload.put("healthThreshold", properties.getHealthThreshold());
        inspectionPayload.put("passed", pass);
        inspectionPayload.put("occurredAt", now.toString());
        battery.setHeadEventHash(
                appendEvent(battery, EventType.INSPECTION, inspectionPayload, request.getRequestId()));

        if (pass) {
            battery.setStatus(BatteryStatus.AVAILABLE.name());
            Map<String, Object> listingPayload = new LinkedHashMap<>();
            listingPayload.put("batteryCode", battery.getCode());
            listingPayload.put("eventType", EventType.LISTING.name());
            listingPayload.put("stationCode", battery.getCurrentStation());
            listingPayload.put("occurredAt", now.toString());
            battery.setHeadEventHash(
                    appendEvent(battery, EventType.LISTING, listingPayload, request.getRequestId()));
        } else {
            battery.setStatus(BatteryStatus.CHARGING.name());
        }
        battery.setUpdatedAt(now);
        batteryRepository.updateWithLock(battery);
        BatteryResponse response = toBatteryResponse(battery);
        finishIdempotent(ctx, response);
        return new OperationResult<>(response, false);
    }

    /** 换出：站点库存扣减并绑定车辆；并发唯一成功，在途/非上架状态禁止换出。以 requestId 幂等。 */
    @Transactional
    public OperationResult<BatteryResponse> swapOut(SwapOutRequest request) {
        BatteryPO battery = lockBatteryOr404(request.getBatteryCode());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batteryCode", request.getBatteryCode());
        fields.put("stationCode", request.getStationCode());
        fields.put("vehicleCode", request.getVehicleCode());
        IdempotentContext ctx = beginIdempotent("SWAP_OUT", request.getRequestId(), fields);
        if (ctx.replayed != null) {
            return new OperationResult<>(ctx.readAs(BatteryResponse.class), true);
        }

        if (!BatteryStatus.AVAILABLE.name().equals(battery.getStatus())) {
            throw new ConflictException("电池当前不可换出: " + battery.getStatus());
        }
        if (!request.getStationCode().equals(battery.getCurrentStation())) {
            throw new ConflictException("电池不在请求站点库存中: station=" + request.getStationCode());
        }
        Instant now = Instant.now(clock);
        battery.setStatus(BatteryStatus.IN_USE.name());
        battery.setCurrentStation(null);
        battery.setBoundVehicle(request.getVehicleCode());
        battery.setUpdatedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batteryCode", battery.getCode());
        payload.put("eventType", EventType.SWAP_OUT.name());
        payload.put("stationCode", request.getStationCode());
        payload.put("vehicleCode", request.getVehicleCode());
        payload.put("occurredAt", now.toString());
        battery.setHeadEventHash(appendEvent(battery, EventType.SWAP_OUT, payload, request.getRequestId()));
        batteryRepository.updateWithLock(battery);
        BatteryResponse response = toBatteryResponse(battery);
        finishIdempotent(ctx, response);
        return new OperationResult<>(response, false);
    }

    /** 站间调拨调出（调出方发起）；非上架或存在未终态调拨单时禁止。以 requestId 幂等。 */
    @Transactional
    public OperationResult<TransferResponse> dispatchTransfer(TransferDispatchRequest request) {
        if (request.getFromStation().equals(request.getToStation())) {
            throw new ConflictException("调入站点与调出站点不能相同");
        }
        BatteryPO battery = lockBatteryOr404(request.getBatteryCode());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batteryCode", request.getBatteryCode());
        fields.put("fromStation", request.getFromStation());
        fields.put("toStation", request.getToStation());
        IdempotentContext ctx = beginIdempotent("TRANSFER_DISPATCH", request.getRequestId(), fields);
        if (ctx.replayed != null) {
            return new OperationResult<>(ctx.readAs(TransferResponse.class), true);
        }

        if (!BatteryStatus.AVAILABLE.name().equals(battery.getStatus())) {
            throw new ConflictException("电池当前不允许调拨: " + battery.getStatus());
        }
        if (!request.getFromStation().equals(battery.getCurrentStation())) {
            throw new ConflictException("电池不在调出站点库存中: station=" + request.getFromStation());
        }
        TransferOrderPO open = transferRepository.lockOpenByBattery(battery.getCode());
        if (open != null) {
            throw new ConflictException("电池存在未终态调拨单，禁止再次调拨: " + open.getOrderNo());
        }

        Instant now = Instant.now(clock);
        TransferOrderPO order = new TransferOrderPO();
        order.setOrderNo(generateOrderNo(now));
        order.setBatteryCode(battery.getCode());
        order.setFromStation(request.getFromStation());
        order.setToStation(request.getToStation());
        order.setStatus(TransferStatus.DISPATCHED.name());
        order.setDispatchedAt(now);
        order.setVersion(0L);
        transferRepository.insert(order);

        battery.setStatus(BatteryStatus.IN_TRANSIT.name());
        battery.setCurrentStation(null);
        battery.setUpdatedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batteryCode", battery.getCode());
        payload.put("eventType", EventType.TRANSFER_DISPATCH.name());
        payload.put("orderNo", order.getOrderNo());
        payload.put("fromStation", request.getFromStation());
        payload.put("toStation", request.getToStation());
        payload.put("occurredAt", now.toString());
        battery.setHeadEventHash(
                appendEvent(battery, EventType.TRANSFER_DISPATCH, payload, request.getRequestId()));
        batteryRepository.updateWithLock(battery);

        TransferResponse response = toTransferResponse(order);
        finishIdempotent(ctx, response);
        return new OperationResult<>(response, false);
    }

    /** 调入方到站签收；仅在途单可签收，签收站点须匹配，异常单须核销。以 requestId 幂等。 */
    @Transactional
    public OperationResult<TransferResponse> receiveTransfer(TransferReceiveRequest request) {
        // 先无锁定位调拨单以取得电池编码，随后按“先电池、后调拨单”的统一顺序加行锁，避免交叉死锁。
        TransferOrderPO located = transferRepository.findByOrderNo(request.getOrderNo());
        if (located == null) {
            throw new NotFoundException("调拨单不存在: " + request.getOrderNo());
        }
        BatteryPO battery = lockBatteryOr404(located.getBatteryCode());
        TransferOrderPO order = transferRepository.lockByOrderNo(request.getOrderNo());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderNo", request.getOrderNo());
        fields.put("toStation", request.getToStation());
        IdempotentContext ctx = beginIdempotent("TRANSFER_RECEIVE", request.getRequestId(), fields);
        if (ctx.replayed != null) {
            return new OperationResult<>(ctx.readAs(TransferResponse.class), true);
        }

        if (!TransferStatus.DISPATCHED.name().equals(order.getStatus())) {
            throw new ConflictException("调拨单当前状态不允许签收: " + order.getStatus());
        }
        if (!order.getToStation().equals(request.getToStation())) {
            throw new ConflictException("签收站点与调入方不一致: " + request.getToStation());
        }
        Instant now = Instant.now(clock);
        order.setStatus(TransferStatus.RECEIVED.name());
        order.setReceivedAt(now);
        int rows = transferRepository.compareAndUpdate(order, TransferStatus.DISPATCHED.name());
        if (rows == 0) {
            throw new ConflictException("调拨单状态已变化，签收失败: " + order.getOrderNo());
        }

        battery.setStatus(BatteryStatus.AT_STATION.name());
        battery.setCurrentStation(order.getToStation());
        battery.setUpdatedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batteryCode", battery.getCode());
        payload.put("eventType", EventType.TRANSFER_RECEIVE.name());
        payload.put("orderNo", order.getOrderNo());
        payload.put("toStation", order.getToStation());
        payload.put("occurredAt", now.toString());
        battery.setHeadEventHash(
                appendEvent(battery, EventType.TRANSFER_RECEIVE, payload, request.getRequestId()));
        batteryRepository.updateWithLock(battery);

        TransferResponse response = toTransferResponse(order);
        finishIdempotent(ctx, response);
        return new OperationResult<>(response, false);
    }

    /** 调拨异常人工核销，仅 EXCEPTION 状态可核销且仅一次。以 requestId 幂等。 */
    @Transactional
    public OperationResult<TransferResponse> writeOffTransfer(TransferWriteOffRequest request) {
        TransferOrderPO located = transferRepository.findByOrderNo(request.getOrderNo());
        if (located == null) {
            throw new NotFoundException("调拨单不存在: " + request.getOrderNo());
        }
        BatteryPO battery = lockBatteryOr404(located.getBatteryCode());
        TransferOrderPO order = transferRepository.lockByOrderNo(request.getOrderNo());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderNo", request.getOrderNo());
        fields.put("reason", request.getReason());
        IdempotentContext ctx = beginIdempotent("TRANSFER_WRITEOFF", request.getRequestId(), fields);
        if (ctx.replayed != null) {
            return new OperationResult<>(ctx.readAs(TransferResponse.class), true);
        }

        if (!TransferStatus.EXCEPTION.name().equals(order.getStatus())) {
            throw new ConflictException("仅调拨异常单可核销，当前状态: " + order.getStatus());
        }
        Instant now = Instant.now(clock);
        order.setStatus(TransferStatus.WRITTEN_OFF.name());
        order.setWrittenOffAt(now);
        order.setWriteoffReason(request.getReason());
        int rows = transferRepository.compareAndUpdate(order, TransferStatus.EXCEPTION.name());
        if (rows == 0) {
            throw new ConflictException("调拨单已核销或状态已变化: " + order.getOrderNo());
        }

        battery.setStatus(BatteryStatus.WRITTEN_OFF.name());
        battery.setCurrentStation(null);
        battery.setUpdatedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batteryCode", battery.getCode());
        payload.put("eventType", EventType.TRANSFER_WRITEOFF.name());
        payload.put("orderNo", order.getOrderNo());
        payload.put("reason", request.getReason());
        payload.put("occurredAt", now.toString());
        battery.setHeadEventHash(
                appendEvent(battery, EventType.TRANSFER_WRITEOFF, payload, request.getRequestId()));
        batteryRepository.updateWithLock(battery);

        TransferResponse response = toTransferResponse(order);
        finishIdempotent(ctx, response);
        return new OperationResult<>(response, false);
    }

    /**
     * 扫描在途超过 24 小时（UTC）的调拨单，原子转调拨异常。
     *
     * @return 本次转为异常的调拨单数量
     */
    @Transactional
    public int markTimedOutTransfers() {
        Instant now = Instant.now(clock);
        Instant deadline = now.minus(java.time.Duration.ofHours(properties.getTransferTimeoutHours()));
        // 先无锁读在途单，再按“先电池、后调拨单”的统一顺序加锁，避免与调拨/换出交叉死锁。
        List<TransferOrderPO> dispatched = transferRepository.findDispatched();
        int marked = 0;
        for (TransferOrderPO snapshot : dispatched) {
            if (!snapshot.getDispatchedAt().isBefore(deadline)) {
                continue;
            }
            BatteryPO battery = lockBatteryOr404(snapshot.getBatteryCode());
            TransferOrderPO order = transferRepository.lockByOrderNo(snapshot.getOrderNo());
            if (!TransferStatus.DISPATCHED.name().equals(order.getStatus())) {
                continue;
            }
            order.setStatus(TransferStatus.EXCEPTION.name());
            order.setExceptionAt(now);
            int rows = transferRepository.compareAndUpdate(order, TransferStatus.DISPATCHED.name());
            if (rows == 0) {
                continue;
            }
            battery.setStatus(BatteryStatus.TRANSFER_EXCEPTION.name());
            battery.setCurrentStation(null);
            battery.setUpdatedAt(now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batteryCode", battery.getCode());
            payload.put("eventType", EventType.TRANSFER_EXCEPTION.name());
            payload.put("orderNo", order.getOrderNo());
            payload.put("dispatchedAt", order.getDispatchedAt().toString());
            payload.put("timeoutHours", properties.getTransferTimeoutHours());
            payload.put("occurredAt", now.toString());
            battery.setHeadEventHash(appendEvent(battery, EventType.TRANSFER_EXCEPTION, payload, null));
            batteryRepository.updateWithLock(battery);
            marked++;
        }
        return marked;
    }

    /** 查询电池资产，不存在抛 404。 */
    public BatteryResponse getBattery(String code) {
        BatteryPO battery = batteryRepository.findByCode(code);
        if (battery == null) {
            throw new NotFoundException("电池不存在: " + code);
        }
        return toBatteryResponse(battery);
    }

    /** 查询电池事件链（按序号稳定升序）。 */
    public List<EventResponse> getEvents(String batteryCode) {
        if (batteryRepository.findByCode(batteryCode) == null) {
            throw new NotFoundException("电池不存在: " + batteryCode);
        }
        List<EventResponse> responses = new ArrayList<>();
        for (LedgerEventPO event : eventRepository.findByBattery(batteryCode)) {
            responses.add(toEventResponse(event));
        }
        return responses;
    }

    /** 校验电池事件链，返回校验结果视图。 */
    public ChainVerificationView verifyChain(String batteryCode) {
        if (batteryRepository.findByCode(batteryCode) == null) {
            throw new NotFoundException("电池不存在: " + batteryCode);
        }
        ChainVerifier.VerificationResult result = chainVerifier.verify(batteryCode);
        return new ChainVerificationView(batteryCode, result.isValid(), result.getFailedSeq(),
                result.getReason(), result.getHeadHash());
    }

    // ---- 内部辅助 ----

    private BatteryPO lockBatteryOr404(String code) {
        BatteryPO battery = batteryRepository.lockByCode(code);
        if (battery == null) {
            throw new NotFoundException("电池不存在: " + code);
        }
        return battery;
    }

    /**
     * 在电池行锁保护下追加事件：序号取链内最大值 +1，prevHash 取电池当前链头。
     */
    private String appendEvent(BatteryPO battery, EventType eventType, Map<String, Object> payload,
            String requestId) {
        long seq = eventRepository.maxSeq(battery.getCode()) + 1;
        String prevHash = battery.getHeadEventHash() == null
                ? HashChainUtil.GENESIS_PREV_HASH : battery.getHeadEventHash();
        String eventHash = HashChainUtil.eventHash(prevHash, payload);
        LedgerEventPO event = new LedgerEventPO();
        event.setBatteryCode(battery.getCode());
        event.setSeq(seq);
        event.setEventType(eventType.name());
        event.setPrevHash(prevHash);
        event.setEventHash(eventHash);
        event.setPayload(PayloadCodec.encode(payload));
        event.setRequestId(requestId);
        event.setOccurredAt(Instant.parse(String.valueOf(payload.get("occurredAt"))));
        eventRepository.insert(event);
        return eventHash;
    }

    private IdempotentContext beginIdempotent(String operation, String requestId, Map<String, Object> fields) {
        String fingerprint = HashChainUtil.fingerprint(operation, requestId, fields);
        IdempotentRecordPO existing = idempotentRepository.findByRequestId(requestId);
        if (existing != null) {
            if (!existing.getFingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已用于不同业务参数: " + requestId);
            }
            return IdempotentContext.replay(existing);
        }
        return IdempotentContext.first(operation, requestId, fingerprint);
    }

    private void finishIdempotent(IdempotentContext ctx, Object response) {
        IdempotentRecordPO record = new IdempotentRecordPO();
        record.setRequestId(ctx.requestId);
        record.setOperation(ctx.operation);
        record.setFingerprint(ctx.fingerprint);
        record.setResultStatus(200);
        record.setResultBody(writeJson(response));
        record.setCreatedAt(Instant.now(clock));
        try {
            idempotentRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 极端并发下回查：同参回放，异参 409（当前事务随之回滚）。
            IdempotentRecordPO existing = idempotentRepository.findByRequestId(ctx.requestId);
            if (existing != null && existing.getFingerprint().equals(ctx.fingerprint)) {
                ctx.markReplayed(existing);
                return;
            }
            throw new ConflictException("requestId 已用于不同业务参数: " + ctx.requestId);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String generateOrderNo(Instant now) {
        return "TO-" + Long.toHexString(now.toEpochMilli()) + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0x1000000));
    }

    private BatteryResponse toBatteryResponse(BatteryPO po) {
        BatteryResponse response = new BatteryResponse();
        response.setCode(po.getCode());
        response.setStatus(po.getStatus());
        response.setCurrentStation(po.getCurrentStation());
        response.setBoundVehicle(po.getBoundVehicle());
        response.setHeadEventHash(po.getHeadEventHash());
        response.setCreatedAt(po.getCreatedAt());
        response.setUpdatedAt(po.getUpdatedAt());
        return response;
    }

    private EventResponse toEventResponse(LedgerEventPO po) {
        EventResponse response = new EventResponse();
        response.setBatteryCode(po.getBatteryCode());
        response.setSeq(po.getSeq());
        response.setEventType(po.getEventType());
        response.setPrevHash(po.getPrevHash());
        response.setEventHash(po.getEventHash());
        response.setPayload(po.getPayload());
        response.setRequestId(po.getRequestId());
        response.setOccurredAt(po.getOccurredAt());
        return response;
    }

    private TransferResponse toTransferResponse(TransferOrderPO po) {
        TransferResponse response = new TransferResponse();
        response.setOrderNo(po.getOrderNo());
        response.setBatteryCode(po.getBatteryCode());
        response.setFromStation(po.getFromStation());
        response.setToStation(po.getToStation());
        response.setStatus(po.getStatus());
        response.setDispatchedAt(po.getDispatchedAt());
        response.setReceivedAt(po.getReceivedAt());
        response.setExceptionAt(po.getExceptionAt());
        response.setWrittenOffAt(po.getWrittenOffAt());
        response.setWriteoffReason(po.getWriteoffReason());
        return response;
    }

    /** 事件链校验结果视图。 */
    public record ChainVerificationView(String batteryCode, boolean valid, long failedSeq, String reason,
            String headHash) {
    }

    /** 一次幂等请求的上下文。 */
    private static final class IdempotentContext {

        private final String operation;
        private final String requestId;
        private final String fingerprint;
        private IdempotentRecordPO replayed;

        private IdempotentContext(String operation, String requestId, String fingerprint,
                IdempotentRecordPO replayed) {
            this.operation = operation;
            this.requestId = requestId;
            this.fingerprint = fingerprint;
            this.replayed = replayed;
        }

        static IdempotentContext first(String operation, String requestId, String fingerprint) {
            return new IdempotentContext(operation, requestId, fingerprint, null);
        }

        static IdempotentContext replay(IdempotentRecordPO record) {
            return new IdempotentContext(record.getOperation(), record.getRequestId(),
                    record.getFingerprint(), record);
        }

        void markReplayed(IdempotentRecordPO record) {
            this.replayed = record;
        }

        <T> T readAs(Class<T> type) {
            try {
                return SharedMapper.MAPPER.readValue(replayed.getResultBody(), type);
            } catch (Exception e) {
                throw new IllegalStateException("幂等结果反序列化失败", e);
            }
        }
    }

    /** 回放反序列化使用的独立 Mapper（带 JavaTimeModule）。 */
    private static final class SharedMapper {
        private static final ObjectMapper MAPPER = new ObjectMapper()
                .findAndRegisterModules();
    }
}
