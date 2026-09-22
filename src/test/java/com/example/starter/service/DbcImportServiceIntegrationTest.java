package com.example.starter.service;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.api.dto.DbcImportResponse;
import com.example.starter.domain.ByteOrder;
import com.example.starter.domain.MultiplexRole;
import com.example.starter.domain.VersionDef;
import com.example.starter.exception.ApiConflictException;
import com.example.starter.exception.ApiValidationException;
import com.example.starter.repository.DbcDefinitionRepository;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.TestDbcFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DBC 导入的 H2 集成测试：原子写入、定义回读、requestId 幂等与异参 409、失败不污染。
 */
@SpringBootTest
class DbcImportServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DbcImportService importService;

    @Autowired
    private DbcDefinitionRepository definitionRepository;

    private DbcImportRequest sampleRequest() {
        return TestDbcFactory.version("v1", "req-sample")
                .message(0x100, "EngineData", 4)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 100, "rpm")
                .signal("Temp", "BIG_ENDIAN", 31, 8, true, 0.5, -40, "degC")
                .endMessage()
                .message(0x200, "GearData", 2)
                .signal("Mode", "LITTLE_ENDIAN", 0, 2, false, 1, 0, "", "MULTIPLEXOR", null, null)
                .signal("Gear", "LITTLE_ENDIAN", 2, 4, false, 1, 0, "",
                        "MULTIPLEXED", 1L, Map.of(1L, "D", 2L, "P"))
                .signal("Brake", "LITTLE_ENDIAN", 2, 4, false, 1, 0, "",
                        "MULTIPLEXED", 2L, null)
                .endMessage()
                .build();
    }

    @Test
    void atomicImportPersistsAllDefinitions() {
        DbcImportResponse response = importService.importVersion(sampleRequest());

        assertFalse(response.replayed());
        assertEquals(2, response.messageCount());
        assertEquals(5, response.signalCount());

        VersionDef versionDef = definitionRepository.loadVersionDef("v1").orElseThrow();
        assertEquals(2, versionDef.messages().size());

        VersionDef.MessageDef engine = versionDef.messages().stream()
                .filter(m -> m.canId() == 0x100).findFirst().orElseThrow();
        assertEquals(4, engine.dlc());
        VersionDef.SignalDef rpm = engine.signals().stream()
                .filter(s -> s.name().equals("Rpm")).findFirst().orElseThrow();
        assertEquals(ByteOrder.LITTLE_ENDIAN, rpm.byteOrder());
        assertEquals(0, rpm.startBit());
        assertEquals(16, rpm.bitLength());
        assertFalse(rpm.signed());
        assertEquals(0.25, rpm.factor());
        assertEquals(100, rpm.offset());
        assertEquals("rpm", rpm.unit());
        assertEquals(MultiplexRole.NONE, rpm.multiplexRole());

        VersionDef.SignalDef temp = engine.signals().stream()
                .filter(s -> s.name().equals("Temp")).findFirst().orElseThrow();
        assertEquals(ByteOrder.BIG_ENDIAN, temp.byteOrder());
        assertTrue(temp.signed());

        VersionDef.MessageDef gear = versionDef.messages().stream()
                .filter(m -> m.canId() == 0x200).findFirst().orElseThrow();
        VersionDef.SignalDef mode = gear.signals().stream()
                .filter(s -> s.name().equals("Mode")).findFirst().orElseThrow();
        assertEquals(MultiplexRole.MULTIPLEXOR, mode.multiplexRole());
        VersionDef.SignalDef gearSignal = gear.signals().stream()
                .filter(s -> s.name().equals("Gear")).findFirst().orElseThrow();
        assertEquals(MultiplexRole.MULTIPLEXED, gearSignal.multiplexRole());
        assertEquals(1L, gearSignal.multiplexValue());
        assertEquals("D", gearSignal.valueTable().get(1L));
        assertEquals("P", gearSignal.valueTable().get(2L));

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbc_version WHERE version_name = 'v1'", Long.class));
    }

    @Test
    void sameRequestIdSameParamsIsIdempotent() {
        DbcImportResponse first = importService.importVersion(sampleRequest());
        DbcImportResponse replay = importService.importVersion(sampleRequest());

        assertTrue(replay.replayed());
        assertEquals(first.versionId(), replay.versionId());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_version", Long.class));
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_message_def", Long.class));
        assertEquals(5L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_signal_def", Long.class));
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_signal_enum", Long.class));
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndKeepsOriginal() {
        importService.importVersion(sampleRequest());

        DbcImportRequest changed = TestDbcFactory.version("v1", "req-sample")
                .message(0x100, "EngineData", 4)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.5, 100, "rpm")
                .endMessage()
                .build();
        ApiConflictException ex = assertThrows(ApiConflictException.class,
                () -> importService.importVersion(changed));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());

        // 原有版本定义未被污染
        VersionDef versionDef = definitionRepository.loadVersionDef("v1").orElseThrow();
        VersionDef.SignalDef rpm = versionDef.messages().get(0).signals().get(0);
        assertEquals(0.25, rpm.factor());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_version", Long.class));
    }

    @Test
    void duplicateVersionNameReturns409() {
        importService.importVersion(sampleRequest());
        DbcImportRequest other = TestDbcFactory.version("v1", "req-other")
                .message(0x101, "OtherMsg", 1)
                .signal("S1", "LITTLE_ENDIAN", 0, 8, false, 1, 0, "")
                .endMessage()
                .build();
        ApiConflictException ex = assertThrows(ApiConflictException.class,
                () -> importService.importVersion(other));
        assertEquals("VERSION_EXISTS", ex.getCode());
    }

    @Test
    void invalidDefinitionIsRejectedWithoutWriting() {
        DbcImportRequest invalid = TestDbcFactory.version("v-bad", "req-bad")
                .message(0x100, "Msg", 1)
                // startBit=4 长度 8 超出 DLC=1
                .signal("S1", "LITTLE_ENDIAN", 4, 8, false, 1, 0, "")
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> importService.importVersion(invalid));

        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_version", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_message_def", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbc_signal_def", Long.class));
    }

    @Test
    void failedImportDoesNotPolluteExistingVersion() {
        importService.importVersion(sampleRequest());

        DbcImportRequest bad = TestDbcFactory.version("v2", "req-v2")
                .message(0x300, "BadMsg", 1)
                // startBit=4 长度 8 超出 DLC=1
                .signal("S1", "LITTLE_ENDIAN", 4, 8, false, 1, 0, "")
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> importService.importVersion(bad));

        assertNotNull(definitionRepository.findVersionByName("v1").orElse(null));
        VersionDef v1 = definitionRepository.loadVersionDef("v1").orElseThrow();
        assertEquals(2, v1.messages().size());
    }
}
