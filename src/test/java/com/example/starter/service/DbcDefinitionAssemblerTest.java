package com.example.starter.service;

import com.example.starter.dbc.Endian;
import com.example.starter.dbc.InvalidDefinitionException;
import com.example.starter.dbc.MultiplexMode;
import com.example.starter.web.dto.ImportRequest;
import com.example.starter.web.dto.SignalDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DbcDefinitionAssembler} 单元测试：非法定义必须拒绝，内容哈希需对字段差异敏感。
 */
class DbcDefinitionAssemblerTest {

    private final DbcDefinitionAssembler assembler = new DbcDefinitionAssembler();

    private SignalDto signal(String name, int start, int len, Endian endian, boolean signed,
                             MultiplexMode mode, Long muxValue) {
        return new SignalDto(name, start, len, endian, signed, 1.0, 0.0, mode, muxValue, Map.of());
    }

    private ImportRequest request(String requestId, String version, List<ImportRequest.MessageDto> messages) {
        return new ImportRequest(requestId, version, messages);
    }

    private ImportRequest.MessageDto message(long canId, int dlc, List<SignalDto> signals) {
        return new ImportRequest.MessageDto(canId, "MSG" + canId, dlc, signals);
    }

    @Test
    void bitRangeOverflowIsRejected() {
        // dlc=1 但 8 位 Intel 信号起始位为 1，位 8 越界
        ImportRequest req = request("r1", "v1", List.of(message(0x100, 1,
                List.of(signal("s", 1, 8, Endian.LITTLE, false, MultiplexMode.NONE, null)))));
        InvalidDefinitionException ex = assertThrows(InvalidDefinitionException.class,
                () -> assembler.assemble(req));
        assertTrue(ex.getMessage().contains("超出报文负载长度"));
    }

    @Test
    void bigEndianOverflowIsRejected() {
        // start 7 len16 需要 2 字节，dlc=1 必须拒绝
        ImportRequest req = request("r2", "v1", List.of(message(0x100, 1,
                List.of(signal("s", 7, 16, Endian.BIG, false, MultiplexMode.NONE, null)))));
        assertThrows(InvalidDefinitionException.class, () -> assembler.assemble(req));
    }

    @Test
    void duplicateSignalNameIsRejected() {
        ImportRequest req = request("r3", "v1", List.of(message(0x100, 8, List.of(
                signal("s", 0, 8, Endian.LITTLE, false, MultiplexMode.NONE, null),
                signal("s", 8, 8, Endian.LITTLE, false, MultiplexMode.NONE, null)))));
        assertThrows(InvalidDefinitionException.class, () -> assembler.assemble(req));
    }

    @Test
    void duplicateCanIdIsRejected() {
        ImportRequest req = request("r4", "v1", List.of(
                message(0x100, 8, List.of(signal("a", 0, 8, Endian.LITTLE, false,
                        MultiplexMode.NONE, null))),
                message(0x100, 8, List.of(signal("b", 0, 8, Endian.LITTLE, false,
                        MultiplexMode.NONE, null)))));
        assertThrows(InvalidDefinitionException.class, () -> assembler.assemble(req));
    }

    @Test
    void muxedSignalWithoutMuxValueIsRejected() {
        ImportRequest req = request("r5", "v1", List.of(message(0x100, 8, List.of(
                signal("sel", 0, 4, Endian.LITTLE, false, MultiplexMode.MULTIPLEXOR, null),
                signal("x", 8, 8, Endian.LITTLE, false, MultiplexMode.MUXED, null)))));
        assertThrows(InvalidDefinitionException.class, () -> assembler.assemble(req));
    }

    @Test
    void twoMultiplexorsAreRejected() {
        ImportRequest req = request("r6", "v1", List.of(message(0x100, 8, List.of(
                signal("m1", 0, 4, Endian.LITTLE, false, MultiplexMode.MULTIPLEXOR, null),
                signal("m2", 4, 4, Endian.LITTLE, false, MultiplexMode.MULTIPLEXOR, null)))));
        assertThrows(InvalidDefinitionException.class, () -> assembler.assemble(req));
    }

    @Test
    void hashChangesWhenFactorChanges() {
        var messages = List.of(message(0x100, 8, List.of(
                new SignalDto("s", 0, 8, Endian.LITTLE, false, 1.0, 0.0,
                        MultiplexMode.NONE, null, Map.of()))));
        var messages2 = List.of(message(0x100, 8, List.of(
                new SignalDto("s", 0, 8, Endian.LITTLE, false, 2.0, 0.0,
                        MultiplexMode.NONE, null, Map.of()))));
        ImportRequest r1 = request("ignored-id", "v1", messages);
        ImportRequest r2 = request("ignored-id", "v1", messages2);
        String h1 = assembler.contentHash(r1, assembler.assemble(r1));
        String h2 = assembler.contentHash(r2, assembler.assemble(r2));
        assertNotEquals(h1, h2);
    }

    @Test
    void hashStableForSameContentRegardlessOfRequestId() {
        var messages = List.of(message(0x100, 8, List.of(
                signal("s", 0, 8, Endian.LITTLE, false, MultiplexMode.NONE, null))));
        ImportRequest r1 = request("req-A", "v1", messages);
        ImportRequest r2 = request("req-B", "v1", messages);
        assertEquals(assembler.contentHash(r1, assembler.assemble(r1)),
                assembler.contentHash(r2, assembler.assemble(r2)));
    }

    @Test
    void invalidEnumKeyIsRejected() {
        SignalDto bad = new SignalDto("s", 0, 8, Endian.LITTLE, false, 1.0, 0.0,
                MultiplexMode.NONE, null, Map.of("abc", "ON"));
        ImportRequest req = request("r7", "v1", List.of(message(0x100, 8, List.of(bad))));
        assertThrows(InvalidDefinitionException.class, () -> assembler.assemble(req));
    }
}
