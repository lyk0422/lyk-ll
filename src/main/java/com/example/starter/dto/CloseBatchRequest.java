package com.example.starter.dto;

import java.time.LocalDate;

/** 封账请求；batchDate 缺省取当前时刻应归属的开放批次。 */
public record CloseBatchRequest(LocalDate batchDate) {
}
