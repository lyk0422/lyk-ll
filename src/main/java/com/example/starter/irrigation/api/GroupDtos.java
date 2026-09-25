package com.example.starter.irrigation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 轮灌组建组接口报文。
 */
public final class GroupDtos {

    private GroupDtos() {
    }

    /** 编组请求：一次完成轮灌组建档、斗口合成建档与成员面积登记。 */
    public record CreateGroupRequest(
            @NotBlank String groupCode,
            @NotBlank String groupName,
            @NotEmpty List<OutletMember> outlets) {
    }

    /** 组成员斗口。 */
    public record OutletMember(
            @NotBlank String outletCode,
            @NotBlank String outletName,
            @NotNull @Positive Long areaSquareMeter) {
    }

    /** 组信息响应。 */
    public record GroupResponse(
            String groupCode,
            String groupName,
            List<MemberResponse> outlets) {
    }

    /** 组成员响应，面积单位平方米。 */
    public record MemberResponse(
            String outletCode,
            String outletName,
            long areaSquareMeter) {
    }
}
