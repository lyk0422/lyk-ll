package com.example.starter.rosbridge.store;

/** Service 调用行。 */
public record ServiceCallRow(String clientId, String requestId, String serviceName,
                             String status, long deadline, String resultJson,
                             long createdAt, Long completedAt) {

    public static final String PENDING = "PENDING";
    public static final String OK = "OK";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String ERROR = "ERROR";
}
