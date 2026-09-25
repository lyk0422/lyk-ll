package com.example.starter.web.dto;

/**
 * 风机视图。
 */
public record FanView(
        String code,
        String name,
        boolean occupied
) {
}
