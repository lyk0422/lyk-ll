package com.example.starter.cosmetic.api;

/**
 * 产品响应。
 */
public record ProductResponse(long id, String name, String applicant, String status) {
}
