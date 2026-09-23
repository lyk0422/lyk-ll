package com.example.starter.robot;

/**
 * 交付示例与确定性固化测试共用的样例 Xacro 文件内容。
 */
final class SampleFiles {

    /** 被入口 include 的公共底座文件：定义 base_radius 默认值与 base_link。 */
    static final String BASE_XACRO = """
            <?xml version="1.0"?>
            <robot name="parts" xmlns:xacro="http://www.ros.org/wiki/xacro">
              <xacro:property name="base_radius" value="0.1"/>
              <link name="base_link">
                <inertial>
                  <mass value="1.0"/>
                  <inertia ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01"/>
                </inertial>
                <visual>
                  <geometry><sphere radius="${base_radius}"/></geometry>
                </visual>
              </link>
            </robot>
            """;

    /** 入口文件：include 底座，定义 arm_length 默认值，覆盖时以请求值为准。 */
    static final String MAIN_XACRO = """
            <?xml version="1.0"?>
            <robot name="demo" xmlns:xacro="http://www.ros.org/wiki/xacro">
              <xacro:include filename="../includes/base.urdf.xacro"/>
              <xacro:property name="arm_length" value="0.5"/>
              <link name="arm_link">
                <visual>
                  <origin xyz="0 0 0.25" rpy="0 0 0"/>
                  <geometry><cylinder radius="0.05" length="${arm_length}"/></geometry>
                </visual>
              </link>
              <joint name="base_to_arm" type="fixed">
                <parent link="base_link"/>
                <child link="arm_link"/>
              </joint>
            </robot>
            """;

    private SampleFiles() {
    }
}
