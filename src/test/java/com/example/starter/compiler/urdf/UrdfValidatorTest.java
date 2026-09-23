package com.example.starter.compiler.urdf;

import com.example.starter.compiler.core.CompilerException;
import com.example.starter.compiler.core.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultUrdfValidator} 的单元测试：名称唯一、根 link、父子引用、关节环、
 * 数值有限性、惯量主对角项与碰撞/视觉几何完整性。
 */
class UrdfValidatorTest {

    private DefaultUrdfValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DefaultUrdfValidator();
    }

    private static String validRobot(String... extra) {
        return """
                <?xml version="1.0"?>
                <robot name="r">
                  <link name="base">
                    <inertial>
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                      <mass value="2.5"/>
                      <inertia ixx="0.02" iyy="0.02" izz="0.01" ixy="0" ixz="0" iyz="0"/>
                    </inertial>
                    <visual>
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                      <geometry><cylinder radius="0.1" length="0.05"/></geometry>
                    </visual>
                    <collision>
                      <origin xyz="0 0 0" rpy="0 0 0"/>
                      <geometry><cylinder radius="0.1" length="0.05"/></geometry>
                    </collision>
                  </link>
                  <link name="arm">
                    <visual><geometry><sphere radius="0.05"/></geometry></visual>
                  </link>
                  <joint name="base_arm" type="revolute">
                    <parent link="base"/>
                    <child link="arm"/>
                    <origin xyz="0 0 0.1" rpy="0 0 0"/>
                    <axis xyz="0 0 1"/>
                    <limit lower="-1.57" upper="1.57" effort="10" velocity="1.0"/>
                  </joint>
                </robot>
                """;
    }

    @Test
    void acceptsValidRobot() {
        assertThatCode(() -> validator.validate(validRobot())).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateLinkName() {
        String urdf = """
                <robot name="r">
                  <link name="x"/>
                  <link name="x"/>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode()).isEqualTo(ErrorCode.INVALID_URDF))
                .hasMessageContaining("link 名称重复: x");
    }

    @Test
    void rejectsJointNameConflictingWithLink() {
        String urdf = """
                <robot name="r">
                  <link name="x"/>
                  <link name="y"/>
                  <joint name="x" type="fixed"><parent link="x"/><child link="y"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("joint 名称与已有 link 名称冲突: x");
    }

    @Test
    void rejectsTwoRootLinks() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("必须恰有一个根 link");
    }

    @Test
    void rejectsDanglingParentReference() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <joint name="j" type="fixed"><parent link="ghost"/><child link="a"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("parent link 不存在: ghost");
    }

    @Test
    void rejectsDanglingChildReference() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <joint name="j" type="fixed"><parent link="a"/><child link="ghost"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("child link 不存在: ghost");
    }

    @Test
    void rejectsJointCycle() {
        // 每个 link 都有父关节 -> 不存在根 link，必为环。
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <link name="c"/>
                  <joint name="j1" type="fixed"><parent link="a"/><child link="b"/></joint>
                  <joint name="j2" type="fixed"><parent link="b"/><child link="c"/></joint>
                  <joint name="j3" type="fixed"><parent link="c"/><child link="a"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("必须恰有一个根 link")
                .hasMessageContaining("0 个");
    }

    @Test
    void rejectsDetachedCycleBesidesTree() {
        // 一个合法根链 + 一个独立二节点环：根数量为 1 但环上节点不可达。
        String urdf = """
                <robot name="r">
                  <link name="root"/>
                  <link name="x"/>
                  <link name="y"/>
                  <joint name="jx" type="fixed"><parent link="x"/><child link="y"/></joint>
                  <joint name="jy" type="fixed"><parent link="y"/><child link="x"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("不可达");
    }

    @Test
    void rejectsLinkWithTwoParents() {
        String urdf = """
                <robot name="r">
                  <link name="root"/>
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j1" type="fixed"><parent link="root"/><child link="a"/></joint>
                  <joint name="j2" type="fixed"><parent link="root"/><child link="b"/></joint>
                  <joint name="j3" type="fixed"><parent link="a"/><child link="b"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("被多个 joint 作为 child");
    }

    @Test
    void rejectsNonFiniteNumber() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="fixed">
                    <parent link="a"/><child link="b"/>
                    <origin xyz="0 0 NaN" rpy="0 0 0"/>
                  </joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("非有限数值");
    }

    @Test
    void rejectsInfiniteNumber() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="fixed">
                    <parent link="a"/><child link="b"/>
                    <origin xyz="0 0 Infinity" rpy="0 0 0"/>
                  </joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("非有限数值");
    }

    @Test
    void rejectsNonPositiveInertiaDiagonal() {
        String urdf = """
                <robot name="r">
                  <link name="a">
                    <inertial>
                      <mass value="1.0"/>
                      <inertia ixx="0" iyy="0.02" izz="0.01" ixy="0" ixz="0" iyz="0"/>
                    </inertial>
                  </link>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("惯量主对角项必须为正");
    }

    @Test
    void rejectsNegativeInertiaDiagonal() {
        String urdf = """
                <robot name="r">
                  <link name="a">
                    <inertial>
                      <mass value="1.0"/>
                      <inertia ixx="0.01" iyy="-0.5" izz="0.01" ixy="0" ixz="0" iyz="0"/>
                    </inertial>
                  </link>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("惯量主对角项必须为正");
    }

    @Test
    void rejectsMissingGeometry() {
        String urdf = """
                <robot name="r">
                  <link name="a">
                    <collision><origin xyz="0 0 0" rpy="0 0 0"/></collision>
                  </link>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("geometry");
    }

    @Test
    void rejectsIncompleteOrigin() {
        String urdf = """
                <robot name="r">
                  <link name="a">
                    <visual>
                      <origin xyz="0 0 0"/>
                      <geometry><sphere radius="0.1"/></geometry>
                    </visual>
                  </link>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("xyz 与 rpy 必须同时给出");
    }

    @Test
    void rejectsRevoluteJointWithoutLimit() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="revolute">
                    <parent link="a"/><child link="b"/>
                  </joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("必须声明 <limit>");
    }

    @Test
    void rejectsUnknownJointType() {
        String urdf = """
                <robot name="r">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="wobbly"><parent link="a"/><child link="b"/></joint>
                </robot>
                """;
        assertThatThrownBy(() -> validator.validate(urdf))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("type 不合法");
    }

    @Test
    void rejectsEmptyRobot() {
        assertThatThrownBy(() -> validator.validate("<robot name=\"r\"/>"))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("至少需要一个 link");
    }

    @Test
    void rejectsWrongRootElement() {
        assertThatThrownBy(() -> validator.validate("<link name=\"a\"/>"))
                .isInstanceOf(CompilerException.class)
                .hasMessageContaining("根元素必须是 <robot>");
    }
}
