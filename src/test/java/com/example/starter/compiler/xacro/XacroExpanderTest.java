package com.example.starter.compiler.xacro;

import com.example.starter.compiler.api.DependencyInfo;
import com.example.starter.compiler.core.CompilerException;
import com.example.starter.compiler.core.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultXacroExpander} 的单元测试：参数覆盖、include、循环、越界与受限方言边界。
 */
class XacroExpanderTest {

    private DefaultXacroExpander expander;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        expander = new DefaultXacroExpander();
    }

    private void writeFile(String relativePath, String content) throws Exception {
        Path target = workspace.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static final String LINKS_XACRO = """
            <?xml version="1.0"?>
            <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
              <xacro:property name="arm_length" value="0.10"/>
              <link name="arm_link">
                <inertial>
                  <origin xyz="0 0 0" rpy="0 0 0"/>
                  <mass value="1.0"/>
                  <inertia ixx="0.01" iyy="0.01" izz="0.01" ixy="0.0" ixz="0.0" iyz="0.0"/>
                </inertial>
                <collision>
                  <origin xyz="0 0 0" rpy="0 0 0"/>
                  <geometry><box size="0.1 0.1 0.1"/></geometry>
                </collision>
              </link>
            </robot>
            """;

    private static final String MAIN_XACRO = """
            <?xml version="1.0"?>
            <robot name="arm" xmlns:xacro="http://www.ros.org/wiki/xacro">
              <xacro:include filename="sub/links.xacro"/>
              <xacro:property name="arm_length" value="0.42"/>
              <link name="base_link">
                <visual>
                  <origin xyz="0 0 0" rpy="0 0 0"/>
                  <geometry><cylinder radius="0.1" length="0.05"/></geometry>
                </visual>
              </link>
              <joint name="base_to_arm" type="fixed">
                <parent link="base_link"/>
                <child link="arm_link"/>
                <origin xyz="0 0 ${arm_length}" rpy="0 0 0"/>
              </joint>
            </robot>
            """;

    @Test
    void includesFilesAndUsesFileDefaultProperty() throws Exception {
        writeFile("main.xacro", MAIN_XACRO);
        writeFile("sub/links.xacro", LINKS_XACRO);

        ExpansionResult result = expander.expand(workspace, workspace.resolve("main.xacro"), Map.of());

        assertThat(result.urdf()).contains("arm_link").contains("base_link");
        // include 文件定义 0.10，入口文件后定义 0.42 覆盖之。
        assertThat(result.urdf()).contains("0 0 0.42");
        assertThat(result.expansionChain()).containsExactly("main.xacro", "sub/links.xacro");
        assertThat(result.dependencies()).hasSize(2);
        assertThat(result.dependencies()).extracting(DependencyInfo::path)
                .containsExactly("main.xacro", "sub/links.xacro");
        assertThat(result.dependencies()).allSatisfy(dependency ->
                assertThat(dependency.sha256()).matches("[0-9a-f]{64}"));
    }

    @Test
    void requestParametersOverrideFileDefaults() throws Exception {
        writeFile("main.xacro", MAIN_XACRO);
        writeFile("sub/links.xacro", LINKS_XACRO);

        ExpansionResult result = expander.expand(workspace, workspace.resolve("main.xacro"),
                Map.of("arm_length", "0.77"));

        assertThat(result.urdf()).contains("0 0 0.77");
        assertThat(result.urdf()).doesNotContain("0.42");
    }

    @Test
    void propertyCanReferenceAnotherProperty() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="prefix" value="arm"/>
                  <xacro:property name="link_name" value="${prefix}_link"/>
                  <link name="${link_name}"/>
                </robot>
                """);

        ExpansionResult result = expander.expand(workspace, workspace.resolve("main.xacro"), Map.of());

        assertThat(result.urdf()).contains("name=\"arm_link\"");
    }

    @Test
    void undefinedParameterIsBadRequest() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="${never_defined}"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNDEFINED_PARAMETER))
                .hasMessageContaining("never_defined");
    }

    @Test
    void cyclicIncludeReturns422WithFullChain() throws Exception {
        writeFile("a.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="b.xacro"/>
                  <link name="la"/>
                </robot>
                """);
        writeFile("b.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="a.xacro"/>
                  <link name="lb"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("a.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.CYCLIC_INCLUDE))
                .hasMessageContaining("a.xacro -> b.xacro -> a.xacro");
    }

    @Test
    void selfIncludeIsCycle() throws Exception {
        writeFile("self.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="self.xacro"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("self.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.CYCLIC_INCLUDE))
                .hasMessageContaining("self.xacro -> self.xacro");
    }

    @Test
    void includeOutsideWorkspaceIsBadRequest() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../../secret.xacro"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void missingIncludeIsBadRequest() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="not-there.xacro"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("not-there.xacro");
    }

    @Test
    void missingEntryIsBadRequest() {
        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("nope.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void unsupportedMacroElementRejected() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:macro name="make_link" params="n"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO))
                .hasMessageContaining("xacro:macro");
    }

    @Test
    void unsupportedIfElementRejected() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:if value="true"><link name="x"/></xacro:if>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO));
    }

    @Test
    void unsupportedXacroAttributeRejected() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="x" xacro:abc="1"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO))
                .hasMessageContaining("xacro:abc");
    }

    @Test
    void scopedPropertyRejected() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="p" value="1" scope="parent"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO));
    }

    @Test
    void unsupportedArgElementRejected() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:arg name="p" default="1"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO));
    }

    @Test
    void commentsAreStrippedAndNotParameterized() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <!-- 注释里的 ${not_a_real_param} 不应被替换，注释本身也不进入结果 -->
                  <link name="real"/>
                </robot>
                """);

        ExpansionResult result = expander.expand(workspace, workspace.resolve("main.xacro"), Map.of());

        assertThat(result.urdf()).doesNotContain("not_a_real_param");
        assertThat(result.urdf()).doesNotContain("<!--");
        assertThat(result.urdf()).contains("real");
    }

    @Test
    void arithmeticExpressionIsUnsupported() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="a" value="1"/>
                  <link name="x" foo="${a + 1}"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO))
                .hasMessageContaining("表达式");
    }

    @Test
    void unclosedExpressionIsBadRequest() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="x" foo="${a"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("未正确闭合");
    }

    @Test
    void cyclicPropertyReferenceIsBadRequest() throws Exception {        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="a" value="${b}"/>
                  <xacro:property name="b" value="${a}"/>
                  <link name="${a}"/>
                </robot>
                """);

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void invalidXmlIsBadRequest() throws Exception {
        writeFile("main.xacro", "<robot name='r'><link></robot>");

        assertThatThrownBy(() -> expander.expand(workspace, workspace.resolve("main.xacro"), Map.of()))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void repeatedIncludeIsVisitedOnceInChain() throws Exception {
        writeFile("main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="shared.xacro"/>
                  <xacro:include filename="shared.xacro"/>
                  <link name="root"/>
                </robot>
                """);
        writeFile("shared.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="shared_link"/>
                </robot>
                """);

        ExpansionResult result = expander.expand(workspace, workspace.resolve("main.xacro"), Map.of());

        // 注意：重复 include 会让同名 link 出现两次（交给 URDF 校验拦截），
        // 但依赖清单与展开链路中每个文件只计一次。
        assertThat(result.expansionChain()).containsExactly("main.xacro", "shared.xacro");
        assertThat(result.dependencies()).hasSize(2);
    }
}
