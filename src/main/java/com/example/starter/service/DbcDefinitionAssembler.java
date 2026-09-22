package com.example.starter.service;

import com.example.starter.dbc.Endian;
import com.example.starter.dbc.InvalidDefinitionException;
import com.example.starter.dbc.MessageDefinition;
import com.example.starter.dbc.MultiplexMode;
import com.example.starter.dbc.SignalDefinition;
import com.example.starter.web.dto.ImportRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 将导入 DTO 装配为领域定义，并执行跨字段合法性校验；同时产出用于幂等判定的规范化内容哈希。
 * 任何一项非法都抛 {@link InvalidDefinitionException}，由调用方在事务中整体回滚。
 */
@Component
public class DbcDefinitionAssembler {

    /** CAN 2.0A/2.0B 11/29 位标识符上限。 */
    private static final long MAX_CAN_ID = (1L << 29) - 1L;
    /** 经典 CAN 最大负载长度（字节）。 */
    private static final int MAX_DLC = 8;

    /**
     * 装配并校验整个导入请求。
     */
    public List<MessageDefinition> assemble(ImportRequest request) {
        List<MessageDefinition> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (ImportRequest.MessageDto messageDto : request.messages()) {
            long canId = messageDto.canId();
            if (canId < 0 || canId > MAX_CAN_ID) {
                throw new InvalidDefinitionException("CAN ID 超出 0..0x1FFFFFFF: " + canId);
            }
            if (!seenIds.add(canId)) {
                throw new InvalidDefinitionException("版本内 CAN ID 重复: " + canId);
            }
            result.add(assembleMessage(messageDto));
        }
        return result;
    }

    private MessageDefinition assembleMessage(ImportRequest.MessageDto dto) {
        long canId = dto.canId();
        int dlc = dto.dlc();
        if (dlc < 0 || dlc > MAX_DLC) {
            throw new InvalidDefinitionException("报文 " + canId + " 的 dlc 必须在 0..8: " + dlc);
        }
        List<SignalDefinition> signals = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean hasMultiplexor = false;
        for (var signalDto : dto.signals()) {
            String name = signalDto.name();
            if (!names.add(name)) {
                throw new InvalidDefinitionException("报文 " + canId + " 内信号名重复: " + name);
            }
            int length = signalDto.length();
            int startBit = signalDto.startBit();
            if (length < 1 || length > 64) {
                throw new InvalidDefinitionException("信号 " + name + " 位长必须在 1..64: " + length);
            }
            if (startBit < 0 || startBit > 63) {
                throw new InvalidDefinitionException("信号 " + name + " 起始位必须在 0..63: " + startBit);
            }
            validateBitRange(name, startBit, length, signalDto.endian(), dlc);
            MultiplexMode mode = signalDto.multiplexMode();
            Long muxValue = signalDto.muxValue();
            if (mode == MultiplexMode.MULTIPLEXOR) {
                if (hasMultiplexor) {
                    throw new InvalidDefinitionException("报文 " + canId + " 存在多个多路选择信号");
                }
                hasMultiplexor = true;
                if (muxValue != null) {
                    throw new InvalidDefinitionException("多路选择信号 " + name + " 不允许 muxValue");
                }
            } else if (mode == MultiplexMode.MUXED) {
                if (muxValue == null) {
                    throw new InvalidDefinitionException("被复用信号 " + name + " 必须指定 muxValue");
                }
            } else if (muxValue != null) {
                throw new InvalidDefinitionException("普通信号 " + name + " 不允许 muxValue");
            }
            double factor = signalDto.factor() == null ? 1.0d : signalDto.factor();
            double offset = signalDto.offset() == null ? 0.0d : signalDto.offset();
            if (factor == 0.0d || Double.isNaN(factor) || Double.isInfinite(factor)
                    || Double.isNaN(offset) || Double.isInfinite(offset)) {
                throw new InvalidDefinitionException("信号 " + name + " 的 factor/offset 非法");
            }
            signals.add(new SignalDefinition(
                    name, startBit, length, signalDto.endian(), signalDto.signed(),
                    factor, offset, mode, muxValue, parseEnumerations(canId, name, signalDto.enumerations())));
        }
        return new MessageDefinition(canId, dto.name(), dlc, signals);
    }

    /**
     * 校验信号占用的所有位都落在 dlc 字节范围内，且同字节内不做重叠检查之外的假设；
     * 这里逐位计算最大字节下标，保证大小端两种布局都不越界。
     */
    private void validateBitRange(String name, int startBit, int length, Endian endian, int dlc) {
        int bitPos = startBit;
        for (int i = 0; i < length; i++) {
            int byteIndex = bitPos / 8;
            if (byteIndex >= dlc) {
                throw new InvalidDefinitionException(
                        "信号 " + name + " 位 " + bitPos + " 超出报文负载长度 " + dlc + " 字节");
            }
            if (endian == Endian.LITTLE) {
                bitPos++;
            } else {
                bitPos = nextBigEndianBitForValidation(bitPos);
            }
        }
    }

    private int nextBigEndianBitForValidation(int bitPos) {
        if (bitPos % 8 == 0) {
            return bitPos + 15;
        }
        return bitPos - 1;
    }

    private Map<Long, String> parseEnumerations(long canId, String signalName, Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            long key;
            try {
                key = Long.parseLong(entry.getKey().trim());
            } catch (NumberFormatException ex) {
                throw new InvalidDefinitionException(
                        "报文 " + canId + " 信号 " + signalName + " 的枚举键必须为整数: " + entry.getKey());
            }
            parsed.put(key, entry.getValue());
        }
        return parsed;
    }

    /**
     * 计算请求的规范化内容哈希（覆盖 version 与全部定义字段，忽略 requestId）。
     * 同参重放哈希相同；任何定义差异都会得到不同哈希。
     */
    public String contentHash(ImportRequest request, List<MessageDefinition> messages) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("v=").append(request.version()).append(';');
        for (MessageDefinition message : messages) {
            canonical.append("m{id=").append(message.canId())
                    .append(",name=").append(message.name())
                    .append(",dlc=").append(message.dlc()).append(';');
            for (SignalDefinition signal : message.signals()) {
                canonical.append("s{name=").append(signal.name())
                        .append(",start=").append(signal.startBit())
                        .append(",len=").append(signal.length())
                        .append(",end=").append(signal.endian())
                        .append(",signed=").append(signal.signed())
                        .append(",factor=").append(signal.factor())
                        .append(",offset=").append(signal.offset())
                        .append(",mode=").append(signal.multiplexMode())
                        .append(",mux=").append(signal.muxValue())
                        .append(",enums=");
                new TreeMap<>(signal.enumerations()).forEach((k, v) ->
                        canonical.append(k).append('=').append(v).append(','));
                canonical.append("};");
            }
            canonical.append("};");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
