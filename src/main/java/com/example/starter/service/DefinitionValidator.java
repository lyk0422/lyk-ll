package com.example.starter.service;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.domain.ByteOrder;
import com.example.starter.domain.MultiplexRole;
import com.example.starter.exception.ApiValidationException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * DBC 导入定义的跨字段语义校验：枚举解析、位范围、字节序、多路复用配置。
 * 任一规则不满足都抛出 {@link ApiValidationException}（HTTP 400），整个导入不会开始写入。
 */
@Component
public class DefinitionValidator {

    /** 29 位扩展帧 ID 上限。 */
    private static final long MAX_CAN_ID = 0x1FFFFFFFL;
    private static final int MAX_DLC = 8;
    private static final int MAX_BITS = 64;

    /**
     * 校验整份导入请求。
     */
    public void validate(DbcImportRequest request) {
        if (request.messages().isEmpty()) {
            throw new ApiValidationException("messages 至少包含一个报文定义");
        }
        Set<Long> canIds = new HashSet<>();
        Set<String> signalNamesGlobal = new HashSet<>();
        for (DbcImportRequest.MessageDefDto message : request.messages()) {
            validateMessage(message, canIds);
            for (DbcImportRequest.SignalDefDto signal : message.signals()) {
                validateSignal(signal, message, signalNamesGlobal);
            }
            validateMultiplex(message);
        }
    }

    private void validateMessage(DbcImportRequest.MessageDefDto message, Set<Long> canIds) {
        if (message.canId() > MAX_CAN_ID) {
            throw new ApiValidationException("CAN ID 超出 29 位扩展帧上限: " + message.canId());
        }
        if (message.dlc() < 1 || message.dlc() > MAX_DLC) {
            throw new ApiValidationException("报文 " + message.name() + " 的 dlc 必须在 1..8 之间");
        }
        if (!canIds.add(message.canId())) {
            throw new ApiValidationException("同一版本中 CAN ID 重复定义: 0x"
                    + Long.toHexString(message.canId()));
        }
        if (message.signals().isEmpty()) {
            throw new ApiValidationException("报文 " + message.name() + " 至少包含一个信号");
        }
    }

    private void validateSignal(DbcImportRequest.SignalDefDto signal,
                                DbcImportRequest.MessageDefDto message, Set<String> seen) {
        ByteOrder byteOrder;
        MultiplexRole role;
        try {
            byteOrder = ByteOrder.valueOf(signal.byteOrder());
        } catch (IllegalArgumentException ex) {
            throw new ApiValidationException("信号 " + signal.name() + " 的 byteOrder 非法: "
                    + signal.byteOrder());
        }
        try {
            role = MultiplexRole.valueOf(signal.multiplexRole());
        } catch (IllegalArgumentException ex) {
            throw new ApiValidationException("信号 " + signal.name() + " 的 multiplexRole 非法: "
                    + signal.multiplexRole());
        }
        if (signal.factor() == 0.0 || !Double.isFinite(signal.factor())
                || !Double.isFinite(signal.offset())) {
            throw new ApiValidationException("信号 " + signal.name() + " 的 factor 不能为 0 或非有限值");
        }
        if (signal.startBit() < 0 || signal.startBit() >= MAX_BITS) {
            throw new ApiValidationException("信号 " + signal.name() + " 的 startBit 必须在 0..63 之间");
        }
        if (signal.bitLength() < 1 || signal.bitLength() > MAX_BITS) {
            throw new ApiValidationException("信号 " + signal.name() + " 的 bitLength 必须在 1..64 之间");
        }
        validateBitRange(signal, message, byteOrder);
        if (role == MultiplexRole.MULTIPLEXED && signal.multiplexValue() == null) {
            throw new ApiValidationException("被选择信号 " + signal.name() + " 必须提供 multiplexValue");
        }
        if (role != MultiplexRole.MULTIPLEXED && signal.multiplexValue() != null) {
            throw new ApiValidationException("信号 " + signal.name()
                    + " 仅在 MULTIPLEXED 角色下才能提供 multiplexValue");
        }
        if (!seen.add(message.canId() + "/" + signal.name())) {
            throw new ApiValidationException("报文 0x" + Long.toHexString(message.canId())
                    + " 中信号名重复: " + signal.name());
        }
        if (signal.valueTable() != null) {
            for (Map.Entry<Long, String> entry : signal.valueTable().entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new ApiValidationException("信号 " + signal.name() + " 的枚举文本不能为空");
                }
            }
        }
    }

    /**
     * 按 DBC 位序逐位模拟，确认信号所有位都落在 DLC 范围内。
     */
    private void validateBitRange(DbcImportRequest.SignalDefDto signal,
                                  DbcImportRequest.MessageDefDto message, ByteOrder byteOrder) {
        int totalBits = message.dlc() * 8;
        int byteIndex = signal.startBit() / 8;
        int bitInByte = signal.startBit() % 8;
        for (int i = 0; i < signal.bitLength(); i++) {
            int physicalBit = byteIndex * 8 + bitInByte;
            if (physicalBit < 0 || physicalBit >= totalBits) {
                throw new ApiValidationException("信号 " + signal.name() + " 的位定义超出报文 DLC 范围");
            }
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                bitInByte++;
                if (bitInByte == 8) {
                    bitInByte = 0;
                    byteIndex++;
                }
            } else {
                bitInByte--;
                if (bitInByte < 0) {
                    bitInByte = 7;
                    byteIndex++;
                }
            }
        }
    }

    private void validateMultiplex(DbcImportRequest.MessageDefDto message) {
        long multiplexorCount = message.signals().stream()
                .filter(s -> MultiplexRole.MULTIPLEXOR.name().equals(s.multiplexRole()))
                .count();
        if (multiplexorCount > 1) {
            throw new ApiValidationException("报文 " + message.name() + " 最多只能有一个 MULTIPLEXOR 信号");
        }
        boolean hasMultiplexed = message.signals().stream()
                .anyMatch(s -> MultiplexRole.MULTIPLEXED.name().equals(s.multiplexRole()));
        if (hasMultiplexed && multiplexorCount == 0) {
            throw new ApiValidationException("报文 " + message.name()
                    + " 存在 MULTIPLEXED 信号但缺少 MULTIPLEXOR 选择器");
        }
    }
}
