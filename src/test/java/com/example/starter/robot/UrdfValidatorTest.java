package com.example.starter.robot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UrdfValidator} 单元测试：名称唯一、单根、引用与关节环、数值有限性、
 * 惯量主对角为正及碰撞/视觉几何完整性。
 */
class UrdfValidatorTest {

    private UrdfValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UrdfValidator();
    }

    @Test
    void acceptsValidRobot() {
        validator.validate(validRobot());
    }

    @Test
    void rejectsDuplicateLinkNames() {
        String urdf = validRobot().replace("""
                <link name="arm_link">""", """
                <link name="base_link">""");
        assertInvalid(urdf, "重复");
    }

    @Test
    void rejectsDuplicateJointNames() {
        String urdf = validRobot() + "";
        String duplicated = urdf.replace("name=\"base_to_arm\"", "name=\"j\"")
                .replace("name=\"arm_to_wrist\"", "name=\"j\"");
        assertInvalid(duplicated, "重复");
    }

    @Test
    void rejectsMultipleRootLinks() {
        String urdf = """
                <?xml version="1.0"?>
                <robot name="r">
                  <link name="root_a"/>
                  <link name="root_b"/>
                </robot>
                """;
        assertInvalid(urdf, "根 link");
    }

    @Test
    void rejectsDanglingParentReference() {
        String urdf = """
                <?xml version="1.0"?>
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="fixed">
                    <parent link="ghost"/>
                    <child link="b"/>
                  </joint>
                </robot>
                """;
        assertInvalid(urdf, "parent link 不存在");
    }

    @Test
    void rejectsDanglingChildReference() {
        String urdf = """
                <?xml version="1.0"?>
                <robot name="r">
                  <link name="a"/>
                  <joint name="j" type="fixed">
                    <parent link="a"/>
                    <child link="ghost"/>
                  </joint>
                </robot>
                """;
        assertInvalid(urdf, "child link 不存在");
    }

    @Test
    void rejectsJointCycle() {
        String urdf = """
                <?xml version="1.0"?>
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j1" type="fixed">
                    <parent link="a"/><child link="b"/>
                  </joint>
                  <joint name="j2" type="fixed">
                    <parent link="b"/><child link="a"/>
                  </joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> {
                    CompileException ce = (CompileException) ex;
                    assertThat(ce.errorCode()).isEqualTo(ErrorCode.INVALID_URDF);
                    // 多 child 或环都会命中；两者都属于关节图非法
                    assertThat(ce.getMessage()).containsAnyOf("环", "根 link", "多父");
                });
    }

    @Test
    void rejectsNonFiniteNumbers() {
        String urdf = validRobot().replace("""
                <origin xyz="0 0 0.25" rpy="0 0 0"/>""", """
                <origin xyz="0 0 NaN" rpy="0 0 0"/>""");
        assertInvalid(urdf, "有限");
    }

    @Test
    void rejectsNonPositiveInertiaDiagonal() {
        String urdf = validRobot().replace("""
                ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01\"""",
                """
                        ixx="0" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01\"""");
        assertInvalid(urdf, "惯量主对角项");
    }

    @Test
    void rejectsInertiaInfiniteDiagonal() {
        String urdf = validRobot().replace("iyy=\"0.01\"", "iyy=\"Infinity\"");
        assertInvalid(urdf, "有限");
    }

    @Test
    void rejectsVisualWithoutGeometry() {
        String urdf = validRobot().replace("""
                    <visual name="base_visual">
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                      <geometry><sphere radius="0.1"/></geometry>
                    </visual>
                """, """
                    <visual name="base_visual">
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                    </visual>
                """);
        assertInvalid(urdf, "geometry");
    }

    @Test
    void rejectsCollisionWithMalformedOriginVector() {
        String urdf = validRobot().replace("""
                    <collision name="arm_collision">
                      <origin xyz="0 0 0.25" rpy="0 0 0"/>
                      <geometry><cylinder radius="0.05" length="0.4"/></geometry>
                    </collision>
                """, """
                    <collision name="arm_collision">
                      <origin xyz="0 0" rpy="0 0 0"/>
                      <geometry><cylinder radius="0.05" length="0.4"/></geometry>
                    </collision>
                """);
        assertInvalid(urdf, "需要 3 个分量");
    }

    @Test
    void rejectsRevoluteJointWithoutLimit() {
        String urdf = """
                <?xml version="1.0"?>
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="revolute">
                    <parent link="a"/><child link="b"/>
                    <axis xyz="0 0 1"/>
                  </joint>
                </robot>
                """;
        assertInvalid(urdf, "limit");
    }

    @Test
    void rejectsNonXmlContent() {
        assertInvalid("not a xml at all", "合法 XML");
    }

    private void assertInvalid(String urdf, String messageFragment) {
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> {
                    CompileException ce = (CompileException) ex;
                    assertThat(ce.errorCode()).isEqualTo(ErrorCode.INVALID_URDF);
                    assertThat(ce.getMessage()).contains(messageFragment);
                });
    }

    private String validRobot() {
        return """
                <?xml version="1.0"?>
                <robot name="demo">
                  <link name="base_link">
                    <inertial>
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                      <mass value="1.0"/>
                      <inertia ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01"/>
                    </inertial>
                    <visual name="base_visual">
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                      <geometry><sphere radius="0.1"/></geometry>
                    </visual>
                  </link>
                  <link name="arm_link">
                    <inertial>
                      <mass value="0.5"/>
                      <inertia ixx="0.005" ixy="0" ixz="0" iyy="0.005" iyz="0" izz="0.005"/>
                    </inertial>
                    <collision name="arm_collision">
                      <origin xyz="0 0 0.25" rpy="0 0 0"/>
                      <geometry><cylinder radius="0.05" length="0.4"/></geometry>
                    </collision>
                  </link>
                  <link name="wrist_link"/>
                  <joint name="base_to_arm" type="revolute">
                    <parent link="base_link"/>
                    <child link="arm_link"/>
                    <origin xyz="0 0 0.05" rpy="0 0 0"/>
                    <axis xyz="0 0 1"/>
                    <limit lower="-3.14" upper="3.14" effort="10" velocity="1"/>
                  </joint>
                  <joint name="arm_to_wrist" type="fixed">
                    <parent link="arm_link"/>
                    <child link="wrist_link"/>
                    <origin xyz="0 0 0.5" rpy="0 0 0"/>
                  </joint>
                </robot>
                """;
    }
}
