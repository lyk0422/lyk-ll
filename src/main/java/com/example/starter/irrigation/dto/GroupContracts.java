package com.example.starter.irrigation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 轮灌组与斗口相关请求/响应契约。
 */
public final class GroupContracts {

    private GroupContracts() {
    }

    /**
     * 斗口录入项。
     *
     * @param code   斗口编号，组内唯一
     * @param name   斗口名称
     * @param areaMu 灌溉面积（亩），必须大于 0
     */
    public record OutletInput(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotNull @DecimalMin(value = "0.01") BigDecimal areaMu) {
    }

    /**
     * 创建轮灌组请求。
     *
     * @param code    轮灌组编码，全局唯一
     * @param name    轮灌组名称
     * @param outlets 组内斗口，至少一个
     */
    public record CreateGroupRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotEmpty List<@Valid OutletInput> outlets) {
    }

    /**
     * 斗口视图。
     *
     * @param outletId 斗口 id
     * @param code     斗口编号
     * @param name     斗口名称
     * @param areaMu   灌溉面积（亩）
     */
    public record OutletView(long outletId, String code, String name, BigDecimal areaMu) {
    }

    /**
     * 轮灌组视图。
     *
     * @param groupId 轮灌组 id
     * @param code    轮灌组编码
     * @param name    轮灌组名称
     * @param outlets 组内斗口（按斗口编号排序）
     */
    public record GroupView(long groupId, String code, String name, List<OutletView> outlets) {
    }
}
