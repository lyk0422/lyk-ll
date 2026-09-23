package com.example.starter.robot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性固化测试：对与交付示例工作区字节一致的输入，断言跨进程/跨运行稳定的
 * URDF 哈希与缓存键。任何序列化或规范化改动若破坏字节稳定性，此测试会失败。
 *
 * <p>示例：入口 robots/main.urdf.xacro，include includes/base.urdf.xacro，
 * 请求覆盖 arm_length=0.8（base_radius 取文件默认 0.1）。
 */
class DeterministicHashPinnedTest {

    /** arm_length=0.8 覆盖下规范 URDF 的稳定 SHA-256。 */
    private static final String PINNED_URDF_SHA256 =
            "be4507859d09fe6bd78966326882061bbc8810db06b404c338a2c614466fcdbd";
    /** 入口+全部传递依赖内容摘要+规范化参数对应的稳定缓存键。 */
    private static final String PINNED_CACHE_KEY =
            "a244796bd80fabd74fbb5f57a530d15e0fb6bbb63980820a8d6654d1c3e04066";

    @TempDir
    Path workspace;

    @Test
    void hashesAreStableAcrossRuns() throws IOException {
        Files.createDirectories(workspace.resolve("includes"));
        Files.createDirectories(workspace.resolve("robots"));
        Files.writeString(workspace.resolve("includes/base.urdf.xacro"), SampleFiles.BASE_XACRO,
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("robots/main.urdf.xacro"), SampleFiles.MAIN_XACRO,
                StandardCharsets.UTF_8);

        XacroExpander expander = new XacroExpander();
        UrdfValidator validator = new UrdfValidator();

        ExpansionResult first = expander.expand(workspace, "robots/main.urdf.xacro",
                Map.of("arm_length", "0.8"));
        validator.validate(first.urdf());
        ExpansionResult second = new XacroExpander().expand(workspace, "robots/main.urdf.xacro",
                Map.of("arm_length", "0.8"));

        String urdfHash = Digests.sha256Hex(first.urdf().getBytes(StandardCharsets.UTF_8));
        assertThat(urdfHash).isEqualTo(PINNED_URDF_SHA256);
        // 同输入重复展开字节一致
        assertThat(second.urdf()).isEqualTo(first.urdf());
        assertThat(Digests.sha256Hex(second.urdf())).isEqualTo(PINNED_URDF_SHA256);

        CompileService service = new CompileService(expander, validator, null,
                new RobotCompilerProperties(workspace));
        String cacheKey = service.buildCacheKey("robots/main.urdf.xacro",
                first.dependencies(), first.parameters());
        assertThat(cacheKey).isEqualTo(PINNED_CACHE_KEY);

        // 依赖内容变化必然改变缓存键（依赖摘要参与键计算）
        String changedBase = SampleFiles.BASE_XACRO.replace("0.1", "0.15");
        Files.writeString(workspace.resolve("includes/base.urdf.xacro"), changedBase,
                StandardCharsets.UTF_8);
        ExpansionResult changed = expander.expand(workspace, "robots/main.urdf.xacro",
                Map.of("arm_length", "0.8"));
        String changedKey = service.buildCacheKey("robots/main.urdf.xacro",
                changed.dependencies(), changed.parameters());
        assertThat(changedKey).isNotEqualTo(PINNED_CACHE_KEY);
    }
}
