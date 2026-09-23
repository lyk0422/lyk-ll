package com.example.starter.audit.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地产物存储：将阶段产物写入产物根目录，路径为相对路径。
 *
 * <p>不依赖对象存储或 rosbag 服务；恢复检查点时可回读文件重新计算 SHA-256 核对依赖产物。</p>
 */
@Component
public class ArtifactStore {

    private final Path root;

    public ArtifactStore(@Value("${audit.artifact-root:artifacts}") String artifactRoot) {
        this.root = Paths.get(artifactRoot).toAbsolutePath().normalize();
    }

    /** 按相对路径写入产物字节，自动创建父目录。 */
    public void write(String relativePath, byte[] content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("写入阶段产物失败: " + relativePath, e);
        }
    }

    /** 回读产物字节；文件不存在返回 null，由调用方判定为不可恢复。 */
    public byte[] read(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取阶段产物失败: " + relativePath, e);
        }
    }

    private Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法产物相对路径: " + relativePath);
        }
        return target;
    }
}
