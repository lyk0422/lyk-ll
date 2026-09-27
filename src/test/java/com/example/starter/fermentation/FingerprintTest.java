package com.example.starter.fermentation;

import com.example.starter.fermentation.service.IdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹结构化编码测试：长度前缀编码保证字段边界无歧义，
 * 合法文本含 '|'、':'、';' 等分隔符时不同字段组合仍产生不同指纹。
 */
class FingerprintTest {

    private final IdempotencyService service = new IdempotencyService(null, null);

    @Test
    @DisplayName("相同输入指纹确定一致")
    void deterministic() {
        assertEquals(service.fingerprint("POST /x", "req-1", "a", "b"),
                service.fingerprint("POST /x", "req-1", "a", "b"));
    }

    @Test
    @DisplayName("字段含分隔符时不同字段组合产生不同指纹")
    void separatorInContentIsUnambiguous() {
        // 内容含 '|'：("a|b", "c") 与 ("a", "b|c") 必须区分
        assertNotEquals(service.fingerprint("e", "r", "a|b", "c"),
                service.fingerprint("e", "r", "a", "b|c"));
        // 内容含 ':' 与 ';'（编码自身使用的字符）也必须区分
        assertNotEquals(service.fingerprint("e", "r", "1:a;", "b"),
                service.fingerprint("e", "r", "1:a", ";b"));
        assertNotEquals(service.fingerprint("e", "r", "x;2:ab"),
                service.fingerprint("e", "r", "x", "2:ab"));
        // 字段数不同但拼接文本可能相同的组合必须区分
        assertNotEquals(service.fingerprint("e", "r", "ab"),
                service.fingerprint("e", "r", "a", "b"));
        // 空串字段与缺失字段区分
        assertNotEquals(service.fingerprint("e", "r", ""),
                service.fingerprint("e", "r"));
    }
}
