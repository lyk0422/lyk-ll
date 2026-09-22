package com.example.starter.service;

import com.example.starter.exception.ApiValidationException;
import com.example.starter.testsupport.TestDbcFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefinitionValidator} 单元测试：位越界、非法枚举、多路复用配置冲突等失败分支。
 */
class DefinitionValidatorTest {

    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void validDefinitionPasses() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 0, "rpm")
                .endMessage()
                .build();
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void duplicateCanIdRejected() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "MsgA", 1).signal("S1", "LITTLE_ENDIAN", 0, 4, false, 1, 0, "").endMessage()
                .message(0x100, "MsgB", 1).signal("S2", "LITTLE_ENDIAN", 0, 4, false, 1, 0, "").endMessage()
                .build();
        ApiValidationException ex = assertThrows(ApiValidationException.class,
                () -> validator.validate(request));
        assertTrue(ex.getMessage().contains("CAN ID 重复"));
    }

    @Test
    void signalBitsExceedingDlcRejected() {
        // DLC=1 共 8 位，Intel startBit=4, len=8 会超出
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("S1", "LITTLE_ENDIAN", 4, 8, false, 1, 0, "")
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }

    @Test
    void bigEndianBitsExceedingDlcRejected() {
        // Motorola startBit=15 需要第二字节，但 DLC=1
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("S1", "BIG_ENDIAN", 15, 8, false, 1, 0, "")
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }

    @Test
    void zeroFactorRejected() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("S1", "LITTLE_ENDIAN", 0, 8, false, 0, 0, "")
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }

    @Test
    void multiplexedWithoutValueRejected() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("M", "LITTLE_ENDIAN", 0, 2, false, 1, 0, "", "MULTIPLEXOR", null, null)
                .signal("S1", "LITTLE_ENDIAN", 2, 2, false, 1, 0, "", "MULTIPLEXED", null, null)
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }

    @Test
    void multiplexedWithoutMultiplexorRejected() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("S1", "LITTLE_ENDIAN", 0, 2, false, 1, 0, "", "MULTIPLEXED", 1L, null)
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }

    @Test
    void twoMultiplexorsRejected() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("M1", "LITTLE_ENDIAN", 0, 1, false, 1, 0, "", "MULTIPLEXOR", null, null)
                .signal("M2", "LITTLE_ENDIAN", 1, 1, false, 1, 0, "", "MULTIPLEXOR", null, null)
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }

    @Test
    void invalidByteOrderRejected() {
        var request = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "Msg", 1)
                .signal("S1", "MIDDLE_ENDIAN", 0, 2, false, 1, 0, "")
                .endMessage()
                .build();
        assertThrows(ApiValidationException.class, () -> validator.validate(request));
    }
}
