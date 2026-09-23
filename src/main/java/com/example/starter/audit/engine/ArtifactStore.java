package com.example.starter.audit.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本地产物存储：将合成产物写入工作目录下的相对路径并计算 SHA-256，
 * 恢复时按路径与摘要重新核对。不依赖对象存储或外部服务；
 * 根目录可通过 audit.artifacts.dir 配置，测试使用临时目录。
 */
@Component
public class ArtifactStore {

    private final Path root;
    private final DigestSupport digestSupport;

    public ArtifactStore(@Value("${audit.artifacts.dir:audit-artifacts}") String rootDir,
                         DigestSupport digestSupport) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        this.digestSupport = digestSupport;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建产物目录: " + this.root, e);
        }
    }

    /**
     * 写入阶段产物。
     *
     * @return 相对产物路径与 SHA-256 摘要
     */
    public StoredArtifact store(long taskId, int attempt, String stageCode, byte[] content) {
        String relative = "task-" + taskId + "/" + stageCode + "-attempt" + attempt + ".out";
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法产物路径: " + relative);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入产物失败: " + relative, e);
        }
        return new StoredArtifact(relative, digestSupport.sha256Hex(content));
    }

    /** 按相对路径读取产物并核对 SHA-256；文件缺失或摘要不符返回 false。 */
    public boolean verify(String relativePath, String expectedSha256) {
        if (relativePath == null || expectedSha256 == null) {
            return false;
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return false;
        }
        try {
            byte[] content = Files.readAllBytes(target);
            return digestSupport.sha256Hex(content).equals(expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }

    /** 读取产物文本（诊断用）。 */
    public String readUtf8(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法产物路径: " + relativePath);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取产物失败: " + relativePath, e);
        }
    }

    public record StoredArtifact(String relativePath, String sha256) {
    }
}
