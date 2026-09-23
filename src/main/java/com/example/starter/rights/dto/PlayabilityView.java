package com.example.starter.rights.dto;

/**
 * 可播校验结果。
 */
public record PlayabilityView(
        boolean playable,
        String reason,
        String licenseId,
        Integer licenseVersion) {

    public static PlayabilityView ok(String licenseId, int version) {
        return new PlayabilityView(true, null, licenseId, version);
    }

    public static PlayabilityView blocked(String reason) {
        return new PlayabilityView(false, reason, null, null);
    }
}
