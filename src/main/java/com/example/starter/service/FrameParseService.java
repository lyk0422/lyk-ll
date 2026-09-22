package com.example.starter.service;

import com.example.starter.api.dto.FrameParseRequest;
import com.example.starter.api.dto.FrameParseResponse;
import com.example.starter.api.dto.SignalStateDto;
import com.example.starter.domain.RejectReason;
import com.example.starter.domain.MultiplexRole;
import com.example.starter.domain.SignalStateRow;
import com.example.starter.domain.VersionDef;
import com.example.starter.exception.ApiNotFoundException;
import com.example.starter.exception.ApiValidationException;
import com.example.starter.repository.DbcDefinitionRepository;
import com.example.starter.repository.FrameRepository;
import com.example.starter.support.NanoClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CAN 帧解析与信号最新状态维护服务。
 *
 * <p>处理顺序（同版本 + CAN ID 在事务内以行锁串行）：</p>
 * <ol>
 *   <li>版本/报文定义不存在：UNKNOWN_ID；负载长度不符：BAD_LENGTH；</li>
 *   <li>多路选择器原始值未匹配任何被选择分支：MUX_NOT_MATCH；</li>
 *   <li>完全相同的帧（版本+CAN ID+序号+时戳+负载）重放：幂等返回，不重复产生事件；</li>
 *   <li>旧时戳或较低/相同序号：STALE_FRAME，仅留存审计，不覆盖状态；</li>
 *   <li>其余为最新有效帧：解码、条件覆盖状态并为真正变化的信号产生事件。</li>
 * </ol>
 */
@Service
public class FrameParseService {

    private final DbcDefinitionRepository definitionRepository;
    private final FrameRepository frameRepository;
    private final NanoClock nanoClock;

    public FrameParseService(DbcDefinitionRepository definitionRepository,
                             FrameRepository frameRepository,
                             NanoClock nanoClock) {
        this.definitionRepository = definitionRepository;
        this.frameRepository = frameRepository;
        this.nanoClock = nanoClock;
    }

    /**
     * 按请求指定的 DBC 版本解析一帧并维护信号状态。
     */
    @Transactional
    public FrameParseResponse parse(FrameParseRequest request) {
        VersionDef versionDef = definitionRepository.loadVersionDef(request.version())
                .orElseThrow(() -> new ApiNotFoundException(
                        "DBC 版本不存在: " + request.version()));
        long versionId = versionDef.version().id();

        VersionDef.MessageDef messageDef = versionDef.messages().stream()
                .filter(m -> m.canId() == request.canId())
                .findFirst()
                .orElse(null);
        if (messageDef == null) {
            audit(versionId, request, null, false, RejectReason.UNKNOWN_ID.name());
            return rejected(request, RejectReason.UNKNOWN_ID);
        }

        byte[] payload = parsePayload(request.payloadHex());

        // 对报文定义行加锁：同一版本 + CAN ID 的帧串行，保证状态/事件判定的并发正确性
        definitionRepository.lockMessageDef(messageDef.id());

        if (payload.length != messageDef.dlc()) {
            audit(versionId, request, payload, false, RejectReason.BAD_LENGTH.name());
            return rejected(request, RejectReason.BAD_LENGTH);
        }

        Long multiplexorValue = findMultiplexorValue(messageDef, payload);
        boolean hasMultiplexedBranches = messageDef.signals().stream()
                .anyMatch(s -> s.multiplexRole() == MultiplexRole.MULTIPLEXED);
        boolean anyBranchActive = !hasMultiplexedBranches || messageDef.signals().stream()
                .anyMatch(s -> s.multiplexRole() == MultiplexRole.MULTIPLEXED
                        && multiplexorValue.equals(s.multiplexValue()));
        if (!anyBranchActive) {
            audit(versionId, request, payload, false, RejectReason.MUX_NOT_MATCH.name());
            return rejected(request, RejectReason.MUX_NOT_MATCH);
        }

        if (frameRepository.existsSameFrame(versionId, request.canId(), request.frameSeq(),
                request.timestampNs(), payload)) {
            // 相同帧重放：留存审计，幂等返回解码值，不重复写入状态与事件
            audit(versionId, request, payload, true, null);
            List<FrameParseResponse.DecodedSignal> decoded = decodeActive(messageDef, payload, multiplexorValue);
            return new FrameParseResponse(true, true, false, null,
                    request.canId(), request.frameSeq(), request.timestampNs(), decoded);
        }

        Optional<Long> latestSeq = frameRepository.findLatestAcceptedSeq(versionId, request.canId());
        Optional<Long> latestTs = frameRepository.findLatestAcceptedTimestamp(versionId, request.canId());
        boolean stale = latestSeq.isPresent()
                && (request.frameSeq() <= latestSeq.get() || request.timestampNs() < latestTs.get());
        if (stale) {
            audit(versionId, request, payload, false, RejectReason.STALE_FRAME.name());
            return new FrameParseResponse(false, false, true, RejectReason.STALE_FRAME.name(),
                    request.canId(), request.frameSeq(), request.timestampNs(), List.of());
        }

        List<FrameParseResponse.DecodedSignal> decoded = new ArrayList<>();
        long now = nanoClock.nanoTime();
        for (VersionDef.SignalDef signal : activeSignals(messageDef, multiplexorValue)) {
            long raw = SignalDecoder.extractRaw(payload, signal.byteOrder(), signal.startBit(),
                    signal.bitLength(), signal.signed());
            double physical = raw * signal.factor() + signal.offset();
            String enumText = signal.valueTable().get(raw);

            Long oldRaw = frameRepository.findState(versionId, request.canId(), signal.name())
                    .map(SignalStateRow::rawValue)
                    .orElse(null);
            frameRepository.upsertStateIfNewer(versionId, request.canId(), signal.name(),
                    raw, physical, enumText, request.frameSeq(), request.timestampNs());
            if (oldRaw == null || oldRaw != raw) {
                frameRepository.insertEventIfAbsent(versionId, request.canId(), request.frameSeq(),
                        request.timestampNs(), signal.name(), oldRaw, raw, now);
            }
            decoded.add(new FrameParseResponse.DecodedSignal(signal.name(), raw, physical,
                    signal.unit(), enumText));
        }

        audit(versionId, request, payload, true, null);
        return new FrameParseResponse(true, false, false, null,
                request.canId(), request.frameSeq(), request.timestampNs(), List.copyOf(decoded));
    }

    /**
     * 查询某版本 + CAN ID 下全部信号最新状态。
     */
    public List<SignalStateDto> listStates(String versionName, long canId) {
        VersionDef versionDef = definitionRepository.loadVersionDef(versionName)
                .orElseThrow(() -> new ApiNotFoundException("DBC 版本不存在: " + versionName));
        Map<String, String> units = versionDef.messages().stream()
                .filter(m -> m.canId() == canId)
                .flatMap(m -> m.signals().stream())
                .collect(Collectors.toMap(
                        VersionDef.SignalDef::name, VersionDef.SignalDef::unit, (a, b) -> a));
        return frameRepository.listStates(versionDef.version().id(), canId).stream()
                .map(row -> new SignalStateDto(row.signalName(), row.rawValue(), row.physicalValue(),
                        units.get(row.signalName()), row.enumText(), row.frameSeq(), row.timestampNs()))
                .toList();
    }

    private Long findMultiplexorValue(VersionDef.MessageDef messageDef, byte[] payload) {
        return messageDef.signals().stream()
                .filter(s -> s.multiplexRole() == MultiplexRole.MULTIPLEXOR)
                .findFirst()
                .map(s -> SignalDecoder.extractRaw(payload, s.byteOrder(), s.startBit(),
                        s.bitLength(), s.signed()))
                .orElse(null);
    }

    private List<VersionDef.SignalDef> activeSignals(VersionDef.MessageDef messageDef, Long multiplexorValue) {
        return messageDef.signals().stream()
                .filter(s -> switch (s.multiplexRole()) {
                    case NONE, MULTIPLEXOR -> true;
                    case MULTIPLEXED -> multiplexorValue != null
                            && multiplexorValue.equals(s.multiplexValue());
                })
                .toList();
    }

    private List<FrameParseResponse.DecodedSignal> decodeActive(VersionDef.MessageDef messageDef,
                                                                byte[] payload, Long multiplexorValue) {
        return activeSignals(messageDef, multiplexorValue).stream()
                .map(signal -> {
                    long raw = SignalDecoder.extractRaw(payload, signal.byteOrder(), signal.startBit(),
                            signal.bitLength(), signal.signed());
                    return new FrameParseResponse.DecodedSignal(signal.name(), raw,
                            raw * signal.factor() + signal.offset(), signal.unit(),
                            signal.valueTable().get(raw));
                })
                .toList();
    }

    private byte[] parsePayload(String payloadHex) {
        try {
            return HexFormat.of().parseHex(payloadHex);
        } catch (IllegalArgumentException ex) {
            throw new ApiValidationException("payloadHex 必须为偶数长度的十六进制字符串: " + payloadHex);
        }
    }

    private void audit(long versionId, FrameParseRequest request, byte[] payload,
                       boolean accepted, String rejectReason) {
        frameRepository.insertAudit(versionId, request.canId(), request.frameSeq(),
                request.timestampNs(), payload == null ? new byte[0] : payload,
                accepted, rejectReason, nanoClock.nanoTime());
    }

    private FrameParseResponse rejected(FrameParseRequest request, RejectReason reason) {
        return new FrameParseResponse(false, false, false, reason.name(),
                request.canId(), request.frameSeq(), request.timestampNs(), List.of());
    }
}
