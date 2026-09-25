package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 限电指令行。状态流转：EXECUTING → SUPERSEDED（被新指令作废）或 SEALED（日结封存，终态）。
 */
public record CommandRow(long id, long farmId, String requestId, String fingerprint, int targetKw,
                         String status, LocalDateTime issuedAt, LocalDateTime sealedAt) {

    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_SEALED = "SEALED";
}
