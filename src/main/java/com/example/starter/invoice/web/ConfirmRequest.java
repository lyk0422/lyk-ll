package com.example.starter.invoice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 红字信息表确认请求。
 *
 * @param party   确认方：SELLER销方/BUYER购方
 * @param opinion 确认意见：CONFIRM同意/REJECT拒绝
 * @param comment 确认备注，可空
 */
public record ConfirmRequest(
        @NotNull @Pattern(regexp = "SELLER|BUYER") String party,
        @NotNull @Pattern(regexp = "CONFIRM|REJECT") String opinion,
        @Size(max = 256) String comment) {
}
