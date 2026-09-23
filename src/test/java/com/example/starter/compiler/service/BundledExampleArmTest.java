package com.example.starter.compiler.service;

import com.example.starter.compiler.api.CompileRequest;
import com.example.starter.compiler.api.CompileResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用工程内置 robot-models/example-arm 合成示例的端到端测试，
 * 锁定 include 链路、参数覆盖结果与规范哈希，作为交付文档中的示例预期。
 */
@SpringBootTest
class BundledExampleArmTest {

    @DynamicPropertySource
    static void registerWorkspace(DynamicPropertyRegistry registry) {
        Path workspace = Path.of("robot-models").toAbsolutePath().normalize();
        registry.add("robot.compiler.workspace", () -> workspace.toString());
    }

    @Autowired
    private RobotCompilerService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE robot_compile_cache");
    }

    @Test
    void bundledExampleWithOverrideHasStableHashAndChain() {
        CompileResult result = service.compile(new CompileRequest(
                "example-arm/arm.xacro", Map.of("arm_length", "0.50"), "doc-example"));

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.expansionChain()).containsExactly(
                "example-arm/arm.xacro", "example-arm/sub/gripper.xacro");
        assertThat(result.dependencies()).hasSize(2);
        assertThat(result.urdf()).contains("0 0 0.50");
        assertThat(result.urdf()).contains("<robot name=\"example_arm\">");
        assertThat(result.urdf()).contains("gripper_link").contains("base_link").contains("arm_link");
        assertThat(result.urdf()).doesNotContain("xacro");

        // 规范序列化对 arm_length=0.50 的确定性 SHA-256；任何非内容性差异都不应改变它。
        assertThat(result.sha256()).isEqualTo("1ed4425c7bac7f2e9499e4f5b4fa8a058350391e2583068a0d1ea96bb9c1ab3a");
    }

    @Test
    void bundledExampleUsesFileDefaultWithoutOverrideAndCacheReuses() {
        CompileResult first = service.compile(
                new CompileRequest("example-arm/arm.xacro", Map.of(), "default-1"));

        assertThat(first.cacheHit()).isFalse();
        assertThat(first.urdf()).contains("0 0 0.35");
        // 文件默认值与参数覆盖值必须产生不同结果。
        CompileResult overridden = service.compile(new CompileRequest(
                "example-arm/arm.xacro", Map.of("arm_length", "0.50"), "override"));
        assertThat(overridden.sha256()).isNotEqualTo(first.sha256());

        // 同参数重复请求复用缓存。
        CompileResult second = service.compile(
                new CompileRequest("example-arm/arm.xacro", Map.of(), "default-2"));
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }
}
