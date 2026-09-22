package com.example.starter.ros.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 单个资源声明。资源键 = (category, fullyQualifiedName, interfaceType)。
 *
 * @param category            资源类别：TOPIC/SERVICE/ACTION
 * @param fullyQualifiedName  全限定名称，如 /robot/cmd_vel
 * @param interfaceType       接口类型，如 std_msgs/msg/String
 */
public record ResourceSpec(
        @NotBlank String category,
        @NotBlank String fullyQualifiedName,
        @NotBlank String interfaceType) {
}
