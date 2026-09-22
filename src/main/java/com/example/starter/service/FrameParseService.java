package com.example.starter.service;

import com.example.starter.dbc.ConflictException;
import com.example.starter.dbc.FrameRejectReason;
import com.example.starter.dbc.MessageDefinition;
import com.example.starter.dbc.MultiplexMode;
import com.example.starter.dbc.SignalDecoder;
import com.example.starter.dbc.SignalDefinition;
import com.example.starter.repo.DbcRepository;
import com.example.starter.repo.FrameRepository;
import com.example.starter.web.dto.FrameRequest;
import com.example.starter.web.dto.FrameResult;
import com.example.starter.web.dto.SignalValueDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAN 帧解析服务：按版本解码、多路复用过滤、新旧判定与状态/事件落库。
 *
 * <p>关键规则：
 * <ul>
 *   <li>负载长度超 8 字节 → INVALID_LENGTH；版本下无该 CAN ID → UNKNOWN_ID；
 *       长度与定义 dlc 不符 → INVALID_LENGTH；</li>
 *   <li>存在多路选择信号且定义了被复用信号，但选择值未命中任何一组 → MUX_NOT_MATCHED，整帧不更新状态；</li>
 *   <li>(version, canId, seq) 唯一标识帧：相同帧重放直接返回首次结果，不重复产生事件；
 *       同序号但负载/时戳不同视为冲突（409）；</li>
 *   <li>旧时戳（或时戳相同但序号较低）不得覆盖状态，也不产生事件，但帧仍留存审计。</li>
 * </ul>
 */
@Service
public class FrameParseService {

    /** 经典 CAN 最大负载字节数。 */
    private static final int MAX_PAYLOAD = 8;

    private final DbcRepository dbcRepository;
    private final FrameRepository frameRepository;

    /** 版本定义一旦导入即不可变，可安全缓存；空版本不缓存以免导入后读到旧空值。 */
    private final Map<String, Map<Long, MessageDefinition>> versionCache = new ConcurrentHashMap<>();

    public FrameParseService(DbcRepository dbcRepository, FrameRepository frameRepository) {
        this.dbcRepository = dbcRepository;
        this.frameRepository = frameRepository;
    }

    /**
     * 解析一帧并按规则更新信号最新状态；帧审计始终留存。
     */
    @Transactional
    public FrameResult parse(FrameRequest request) {
        byte[] payload = request.payload() == null ? new byte[0] : request.payload();

        // 重放判定优先：同序号即同一帧，内容必须与首次完全一致。
        FrameRepository.AuditRow existing =
                frameRepository.findAudit(request.version(), request.canId(), request.seq());
        if (existing != null) {
            return handleReplay(existing, request, payload);
        }

        if (payload.length > MAX_PAYLOAD) {
            return rejectAndAudit(request, payload, FrameRejectReason.INVALID_LENGTH);
        }
        MessageDefinition message = loadMessages(request.version()).get(request.canId());
        if (message == null) {
            return rejectAndAudit(request, payload, FrameRejectReason.UNKNOWN_ID);
        }
        if (payload.length != message.dlc()) {
            return rejectAndAudit(request, payload, FrameRejectReason.INVALID_LENGTH);
        }

        DecodedFrame decoded = decode(message, payload);
        if (decoded.rejectReason() != null) {
            return rejectAndAudit(request, payload, decoded.rejectReason());
        }

        if (!insertAudit(request, payload, true, null)) {
            // 并发下同序号帧抢先落库：按重放语义处理。
            FrameRepository.AuditRow concurrent =
                    frameRepository.findAudit(request.version(), request.canId(), request.seq());
            return handleReplay(concurrent, request, payload);
        }

        boolean anyStateUpdated = false;
        for (SignalValueDto value : decoded.values()) {
            SignalDefinition definition = signalByName(message, value.name());
            String enumLabel = definition.enumerations().get(value.rawValue());
            FrameRepository.UpsertOutcome outcome = frameRepository.upsertStateIfNewer(
                    request.version(), request.canId(), value.name(),
                    value.rawValue(), value.physicalValue(), enumLabel,
                    request.timestampNs(), request.seq());
            if (outcome.updated()) {
                anyStateUpdated = true;
                frameRepository.insertEvent(request.version(), request.canId(), value.name(),
                        request.seq(), request.timestampNs(), outcome.oldRaw(), value.rawValue());
            }
        }
        return new FrameResult(true, null, false, anyStateUpdated, decoded.values());
    }

    /**
     * 重放帧：与首次帧内容一致则返回幂等结果（不更新状态、不产生事件）；内容不一致报 409。
     */
    private FrameResult handleReplay(FrameRepository.AuditRow existing, FrameRequest request, byte[] payload) {
        if (!Arrays.equals(existing.payload(), payload) || existing.timestampNs() != request.timestampNs()) {
            throw new ConflictException("version=" + request.version() + ", canId=" + request.canId()
                    + ", seq=" + request.seq() + " 已存在但负载或时戳不同（同序号冲突）");
        }
        if (!existing.accepted()) {
            return rejected(FrameRejectReason.valueOf(existing.reason()));
        }
        MessageDefinition message = loadMessages(request.version()).get(request.canId());
        if (message == null) {
            // 历史接受帧不可能查不到定义（版本不可变），属于数据不一致的防御分支。
            throw new IllegalStateException("已接受帧的报文定义缺失: " + request.canId());
        }
        return new FrameResult(true, null, true, false, decode(message, payload).values());
    }

    private boolean insertAudit(FrameRequest request, byte[] payload, boolean accepted, String reason) {
        return frameRepository.insertAuditIfAbsent(request.version(), request.canId(),
                request.timestampNs(), request.seq(), payload, accepted, reason) != null;
    }

    private FrameResult rejectAndAudit(FrameRequest request, byte[] payload, FrameRejectReason reason) {
        insertAudit(request, payload, false, reason.name());
        return rejected(reason);
    }

    private DecodedFrame decode(MessageDefinition message, byte[] payload) {
        SignalDefinition multiplexor = null;
        Long multiplexorValue = null;
        boolean hasMuxedSignals = false;
        for (SignalDefinition signal : message.signals()) {
            if (signal.multiplexMode() == MultiplexMode.MULTIPLEXOR) {
                multiplexor = signal;
                multiplexorValue = extractSigned(signal, payload);
            } else if (signal.multiplexMode() == MultiplexMode.MUXED) {
                hasMuxedSignals = true;
            }
        }

        List<SignalValueDto> values = new ArrayList<>();
        boolean matchedMuxedGroup = false;
        for (SignalDefinition signal : message.signals()) {
            if (signal.multiplexMode() == MultiplexMode.MUXED) {
                if (multiplexorValue != null && multiplexorValue.longValue() == signal.muxValue()) {
                    matchedMuxedGroup = true;
                    values.add(toValue(signal, payload));
                }
                continue;
            }
            // 普通信号与多路选择信号始终解码输出。
            values.add(toValue(signal, payload));
        }
        if (multiplexor != null && hasMuxedSignals && !matchedMuxedGroup) {
            return new DecodedFrame(null, FrameRejectReason.MUX_NOT_MATCHED);
        }
        return new DecodedFrame(values, null);
    }

    private SignalValueDto toValue(SignalDefinition signal, byte[] payload) {
        long raw = extractSigned(signal, payload);
        double physical = SignalDecoder.toPhysical(raw, signal.factor(), signal.offset());
        String label = signal.enumerations().get(raw);
        return new SignalValueDto(signal.name(), raw, physical, label);
    }

    private long extractSigned(SignalDefinition signal, byte[] payload) {
        long raw = SignalDecoder.extractRaw(payload, signal);
        return SignalDecoder.applySign(raw, signal.length(), signal.signed());
    }

    private SignalDefinition signalByName(MessageDefinition message, String name) {
        return message.signals().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("解码信号未在定义中: " + name));
    }

    private FrameResult rejected(FrameRejectReason reason) {
        return new FrameResult(false, reason.name(), false, false, List.of());
    }

    private Map<Long, MessageDefinition> loadMessages(String version) {
        Map<Long, MessageDefinition> loaded = versionCache.get(version);
        if (loaded != null) {
            return loaded;
        }
        Map<Long, MessageDefinition> fresh = dbcRepository.loadVersion(version);
        if (fresh.isEmpty()) {
            // 不缓存空结果：版本可能在随后导入。
            return fresh;
        }
        versionCache.putIfAbsent(version, fresh);
        return versionCache.get(version);
    }

    /** 解码中间结果：values 与 rejectReason 二选一。 */
    private record DecodedFrame(List<SignalValueDto> values, FrameRejectReason rejectReason) {
    }
}
