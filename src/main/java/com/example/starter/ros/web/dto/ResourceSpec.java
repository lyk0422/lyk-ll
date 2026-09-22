package com.example.starter.ros.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 节点声明的单个资源入参。
 *
 * @param category      资源类别:TOPIC/SERVICE/ACTION
 * @param name          资源名,可为相对名(与节点命名空间拼接)或以/开头的全局限定名
 * @param interfaceType 接口类型,如 std_msgs/msg/String、example_interfaces/srv/AddTwoInts
 */
public record ResourceSpec(
        @NotBlank String category,
        @NotBlank String name,
        @NotBlank String interfaceType
) {
}
