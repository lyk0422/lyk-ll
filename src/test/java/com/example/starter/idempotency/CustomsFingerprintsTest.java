package com.example.starter.idempotency;

import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关流程结构化指纹单元测试：
 * 同键同参一致；版本、集合/分隔符注入、精确时刻、任一业务字段变化都必须区分。
 */
class CustomsFingerprintsTest {

    private static final Instant AT = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T23:59:59Z");

    private CustomsRegisterRequest register(String requestId, String customsNo, int total, Instant at) {
        return new CustomsRegisterRequest(requestId, customsNo, "进口企业", "进口疫苗",
                VALID_UNTIL, total, at);
    }

    @Test
    void sameKeySameParametersHaveSameFingerprint() {
        assertEquals(Fingerprints.customsRegister(register("r-1", "CI-1", 100, AT)),
                Fingerprints.customsRegister(register("r-1", "CI-1", 100, AT)));
    }

    @Test
    void versionOrQuantityOrMomentChangeMustDiffer() {
        String base = Fingerprints.customsBind(
                new CustomsBindRequest("b-1", "CI-1", "B-1", 10, 1, AT));
        assertNotEquals(base, Fingerprints.customsBind(
                new CustomsBindRequest("b-1", "CI-1", "B-1", 10, 2, AT)),
                "expectedVersion 变化必须导致指纹不同");
        assertNotEquals(base, Fingerprints.customsBind(
                new CustomsBindRequest("b-1", "CI-1", "B-1", 11, 1, AT)),
                "申报支数变化必须导致指纹不同");
        assertNotEquals(base, Fingerprints.customsBind(
                new CustomsBindRequest("b-1", "CI-1", "B-1", 10, 1,
                        Instant.parse("2026-09-20T00:00:01Z"))),
                "精确时刻变化必须导致指纹不同");
        assertNotEquals(base, Fingerprints.customsBind(
                new CustomsBindRequest("b-1", "CI-1", "B-2", 10, 1, AT)),
                "批号变化必须导致指纹不同");
    }

    @Test
    void lengthPrefixedEncodingDistinguishesDelimiterInjection() {
        // 情形 A：两个字段 ["ab", "cd"]；情形 B：一个字段，内容恰好伪装成 "ab" 后接
        // 字段分隔与长度前缀 "ab\n2:cd"。裸分隔符方案会混淆，长度前缀必须区分。
        String twoFields = new Fingerprints.CanonicalBuilder("OP").field("ab").field("cd").build();
        String oneInjectedField = new Fingerprints.CanonicalBuilder("OP").field("ab\n2:cd").build();
        assertNotEquals(twoFields, oneInjectedField, "含换行/长度前缀的合法文本不得伪造字段边界");

        // 含 ":" 的合法单值也不应与其它切分混淆，且编码以字节长度开头。
        String colonValue = Fingerprints.encodeScalarForTest("OP", "ab:cd");
        assertTrue(colonValue.startsWith("2:OP"), "操作字段以 UTF-8 字节长度前缀编码");
        assertNotEquals(twoFields, colonValue);
    }

    @Test
    void differentOperationsWithSameFieldsDiffer() {
        CustomsBindRequest bind = new CustomsBindRequest("k", "CI-1", "B-1", 10, 1, AT);
        // 撤销与绑定即便复用同一 requestId 文本，操作标签不同指纹也应不同
        assertNotEquals(Fingerprints.customsBind(bind),
                Fingerprints.customsRevoke(
                        new com.example.starter.dto.CustomsRevokeRequest("k", "CI-1", 1, AT)));
    }
}
