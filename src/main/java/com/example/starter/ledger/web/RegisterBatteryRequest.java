package com.example.starter.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 电池建档请求：以唯一编码建档。
 */
public class RegisterBatteryRequest {

    /** 电池唯一编码。 */
    @NotBlank
    @Size(max = 64)
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
