package com.example.starter.dye;

import com.example.starter.dye.web.dto.ColorMatchResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证全局 Jackson 定制保留 BigDecimal 业务刻度（ΔE=0.8000、克重尾零），
 * 与前端/评测看到的报文一致。
 */
class JacksonScaleTest extends FlowIntegrationTest {

    @Test
    void preservesBigDecimalScale() throws Exception {
        ColorMatchResponse body = new ColorMatchResponse("r", "s",
                new BigDecimal("0.8000"), true);
        String json = objectMapper.writeValueAsString(body);
        assertThat(json).contains("\"deltaE\":0.8000");
    }

    @Test
    void rawColorMatchHttpResponseKeepsScale() throws Exception {
        String raw = postJson("/api/color-match", java.util.Map.of(
                "requestId", "CM-RAW", "sampleCode", "S",
                "stdL", new BigDecimal("0"), "stdA", new BigDecimal("0"),
                "stdB", new BigDecimal("0"), "sampleL", new BigDecimal("0.8000"),
                "sampleA", new BigDecimal("0"), "sampleB", new BigDecimal("0")))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).contains("\"deltaE\":0.8000");
    }
}
