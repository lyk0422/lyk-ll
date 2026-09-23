package com.example.starter.ros;

import java.util.List;

/**
 * 请求不满足 Schema 约束（未知参数、类型不匹配、必填缺失等），映射为 HTTP 400。
 */
public class SchemaValidationException extends RuntimeException {

    private final List<String> errors;

    public SchemaValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
