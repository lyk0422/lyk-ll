package com.example.starter.dto;

/**
 * 迟到资料登记响应。
 */
public record LateDocumentResponse(String batchNo, String status, int acceptedDocumentCount, String note) {
}
