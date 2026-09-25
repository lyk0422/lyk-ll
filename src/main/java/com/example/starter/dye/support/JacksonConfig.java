package com.example.starter.dye.support;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Jackson 定制：BigDecimal 以 toPlainString 输出（不用科学计数法，并保留业务刻度，
 * 如 ΔE=0.8000、克重=2000.000）。幂等指纹使用独立 ObjectMapper，不受此定制影响。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalPlainCustomizer() {
        return builder -> builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                // writeNumber(String) 会重新解析并丢失尾零；writeRawValue 按原文本输出数字
                gen.writeRawValue(value.toPlainString());
            }
        });
    }
}
