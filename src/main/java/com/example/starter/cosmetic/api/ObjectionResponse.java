package com.example.starter.cosmetic.api;

/**
 * 异议受理响应。
 */
public record ObjectionResponse(long objectionId, long registrationId, String registrationStatus, String message) {
}
