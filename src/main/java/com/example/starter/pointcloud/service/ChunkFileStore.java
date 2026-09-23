package com.example.starter.pointcloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 块文件、隔离块与审计日志的本地文件存储。
 *
 * <p>写入流程：先写 {@code .tmp} 临时文件并回读比对，再以原子移动发布为正式块文件。
 * 损坏块移动到 {@code quarantine/} 并在 {@code audit/audit.log} 追加一条 JSON 审计记录。</p>
 */
public class ChunkFileStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkFileStore.class);
    private static final String SUFFIX = ".pcc";

    private final Path rootDir;
    private final Path chunkDir;
    private final Path quarantineDir;
    private final Path auditDir;
    private final Path auditLog;

    public ChunkFileStore(Path rootDir) {
        this.rootDir = rootDir;
        this.chunkDir = rootDir.resolve("chunks");
        this.quarantineDir = rootDir.resolve("quarantine");
        this.auditDir = rootDir.resolve("audit");
        this.auditLog = auditDir.resolve("audit.log");
    }

    /**
     * 初始化目录结构。
     */
    public void init() throws IOException {
        Files.createDirectories(chunkDir);
        Files.createDirectories(quarantineDir);
        Files.createDirectories(auditDir);
    }

    /**
     * 发布结果。
     *
     * @param fileName       正式块文件名
     * @param newlyPublished true 表示本次新发布；false 表示同名文件已存在（重试）
     */
    public record StoredChunk(String fileName, boolean newlyPublished) {
    }

    public Path rootDir() {
        return rootDir;
    }

    public Path chunkDir() {
        return chunkDir;
    }

    /**
     * 将块字节写入临时文件、回读校验后原子发布。
     *
     * @param chunkId   块 ID（仅用于审计/日志）
     * @param fileBytes 完整块文件字节
     * @return 发布结果；目标文件已存在时不覆盖
     */
    public StoredChunk publish(String chunkId, byte[] fileBytes) throws IOException {
        String fileName = chunkFileName(chunkId);
        Path target = chunkDir.resolve(fileName);
        if (Files.exists(target)) {
            return new StoredChunk(fileName, false);
        }

        Path tmp = chunkDir.resolve(".tmp-" + fileName + "-" + System.nanoTime());
        try {
            Files.write(tmp, fileBytes);
            byte[] readBack = Files.readAllBytes(tmp);
            if (!java.util.Arrays.equals(readBack, fileBytes)) {
                throw new IOException("临时块文件回读内容不一致: " + chunkId);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                try {
                    Files.move(tmp, target);
                } catch (java.nio.file.FileAlreadyExistsException exists) {
                    safeDelete(tmp);
                    return new StoredChunk(fileName, false);
                }
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                safeDelete(tmp);
                return new StoredChunk(fileName, false);
            }
            return new StoredChunk(fileName, true);
        } finally {
            safeDelete(tmp);
        }
    }

    /**
     * 读取正式块文件全部字节。
     */
    public byte[] read(String fileName) throws IOException {
        return Files.readAllBytes(chunkDir.resolve(fileName));
    }

    /**
     * 块文件是否仍在正式目录中。
     */
    public boolean exists(String fileName) {
        return Files.exists(chunkDir.resolve(fileName));
    }

    /**
     * 列出所有正式块文件名（{@value #SUFFIX} 结尾），临时文件不在其中。
     */
    public List<String> listChunkFiles() throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(chunkDir, "*" + SUFFIX)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    names.add(p.getFileName().toString());
                }
            }
        }
        return names;
    }

    /**
     * 将损坏块移动到隔离目录并追加审计记录。
     *
     * @param chunkId  块 ID（可能不可得，此时传文件名）
     * @param fileName 正式块文件名
     * @param reason   损坏/隔离原因
     * @return 隔离后的文件名
     */
    public String quarantine(String chunkId, String fileName, String reason) throws IOException {
        Path source = chunkDir.resolve(fileName);
        String isolatedName = fileName + "." + System.currentTimeMillis() + ".quarantined";
        Path dest = quarantineDir.resolve(isolatedName);
        int suffix = 1;
        while (Files.exists(dest)) {
            dest = quarantineDir.resolve(isolatedName + "." + suffix);
            suffix++;
        }
        if (Files.exists(source)) {
            try {
                Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, dest);
            }
        }
        String entry = "{\"at\":\"" + Instant.now() + "\",\"event\":\"QUARANTINE\",\"chunkId\":"
                + json(chunkId) + ",\"fileName\":" + json(fileName)
                + ",\"isolatedAs\":" + json(dest.getFileName().toString())
                + ",\"reason\":" + json(reason) + "}" + System.lineSeparator();
        appendAudit(entry);
        log.warn("块已隔离 chunkId={} file={} reason={}", chunkId, fileName, reason);
        return dest.getFileName().toString();
    }

    /**
     * 追加一条审计日志（进程内串行）。
     */
    private void appendAudit(String line) throws IOException {
        synchronized (auditLog) {
            Files.writeString(auditLog, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /**
     * 由 chunkId 生成确定性、文件系统安全且可逆的块文件名。
     * 保留 {@code [A-Za-z0-9.-]} 字符，其余字符（含下划线）编码为 {@code _XXXX}
     * （UTF-16 代码单元的 4 位十六进制），因此可通过
     * {@link #chunkIdFromFileName(String)} 还原。
     */
    public static String chunkFileName(String chunkId) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunkId.length(); i++) {
            char c = chunkId.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_').append(String.format("%04x", (int) c));
            }
        }
        return sb + SUFFIX;
    }

    /**
     * {@link #chunkFileName(String)} 的逆运算；非法编码返回 {@code null}。
     */
    public static String chunkIdFromFileName(String fileName) {
        if (!fileName.endsWith(SUFFIX)) {
            return null;
        }
        String body = fileName.substring(0, fileName.length() - SUFFIX.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '_' && i + 4 < body.length()) {
                String hex = body.substring(i + 1, i + 5);
                if (hex.matches("[0-9a-f]{4}")) {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void safeDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }
}
