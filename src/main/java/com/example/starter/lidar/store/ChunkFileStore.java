package com.example.starter.lidar.store;

import com.example.starter.lidar.model.ChunkMetadata;
import com.example.starter.lidar.model.Compression;
import com.example.starter.lidar.model.PointDatum;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 块文件存储。
 * <p>文件格式（大端序，平台无关）：
 * 魔数 "LPC1" + 格式版本 byte + 压缩方式 byte + 规范化内容 SHA-256(32B) +
 * chunkId(UTF) + version(long) + source(UTF) + pointCount(int) +
 * startTs/endTs(long) + 包围盒 6 个 double + 规范化长度(long) + 载荷长度(long) + 载荷。</p>
 * <p>写入流程：写临时文件 → 回读校验头部与 SHA-256 → 原子移动发布。
 * GZIP 失败或压缩后不小于原数据时载荷回退为原始字节、压缩方式标记为 NONE，
 * 两种方式下校验和均针对同一份规范化内容。</p>
 */
public class ChunkFileStore {

    /** 块文件魔数。 */
    static final byte[] MAGIC = {'L', 'P', 'C', '1'};
    /** 文件格式版本。 */
    static final byte FORMAT_VERSION = 1;
    /** 块文件后缀。 */
    private static final String FILE_SUFFIX = ".lpc";

    private final Path rootDir;
    private final Path chunksDir;
    private final Path quarantineDir;
    private final Path tmpDir;

    public ChunkFileStore(Path rootDir) {
        this.rootDir = rootDir;
        this.chunksDir = rootDir.resolve("chunks");
        this.quarantineDir = rootDir.resolve("quarantine");
        this.tmpDir = rootDir.resolve("tmp");
    }

    /** 初始化存储目录。 */
    public void init() throws IOException {
        Files.createDirectories(chunksDir);
        Files.createDirectories(quarantineDir);
        Files.createDirectories(tmpDir);
    }

    public Path getRootDir() {
        return rootDir;
    }

    public Path getChunksDir() {
        return chunksDir;
    }

    public Path getQuarantineDir() {
        return quarantineDir;
    }

    /** 已落盘块的文件信息。 */
    public record StoredBlob(String fileName, Compression compression, long storedSize) {
    }

    /** 从块文件读出的内容。 */
    public record LoadedBlob(byte[] normalized, Compression compression, long storedSize) {
    }

    /** 重建扫描单文件结果。 */
    public sealed interface ScanEntry permits ScanOk, ScanBad {
        String fileName();
    }

    /** 扫描成功：可直接入索引的元数据。 */
    public record ScanOk(String fileName, ChunkMetadata metadata) implements ScanEntry {
    }

    /** 扫描失败：文件损坏需隔离与审计。 */
    public record ScanBad(String fileName, String reason) implements ScanEntry {
    }

    /**
     * 将规范化点内容写入临时文件、回读校验后原子发布。
     */
    public StoredBlob publish(String chunkId, long version, String source,
                              List<PointDatum> points, byte[] normalized,
                              String checksum, Compression requested) throws IOException {
        ChunkHeader header = ChunkHeader.from(chunkId, version, source, points, normalized, checksum, requested);
        byte[] payload;
        Compression actual = Compression.NONE;
        if (requested == Compression.GZIP) {
            byte[] gz = tryGzip(normalized);
            if (gz != null && gz.length < normalized.length) {
                payload = gz;
                actual = Compression.GZIP;
            } else {
                payload = normalized;
            }
        } else {
            payload = normalized;
        }

        String fileName = encodeFileName(chunkId);
        Path target = chunksDir.resolve(fileName);
        Path temp = Files.createTempFile(tmpDir, "chunk-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                header.write(out, payload, actual);
                out.flush();
            }
            // 回读临时文件，验证头部与规范化内容校验和后才发布
            verifyTempFile(temp, checksum, normalized.length, payload.length);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 同卷原子移动不被支持时退化为普通替换移动（临时文件仅本进程可见）
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredBlob(fileName, actual, Files.size(target));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 读取并校验块文件：结构、压缩流及规范化内容 SHA-256 任一不符即视为损坏。
     */
    public LoadedBlob load(ChunkMetadata metadata) {
        Path file = chunksDir.resolve(metadata.fileName());
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CorruptChunkException("块文件不可读: " + metadata.chunkId(), e);
        }
        ParsedFile parsed = parse(fileBytes, metadata.fileName());
        byte[] normalized;
        try {
            normalized = parsed.header.compression == Compression.GZIP
                    ? gunzip(parsed.payload)
                    : parsed.payload;
        } catch (IOException e) {
            throw new CorruptChunkException("块解压失败: " + metadata.chunkId(), e);
        }
        String actualChecksum = sha256Hex(normalized);
        if (!actualChecksum.equals(parsed.header.checksum)
                || !actualChecksum.equals(metadata.checksum())) {
            throw new CorruptChunkException("块校验和不匹配: " + metadata.chunkId());
        }
        return new LoadedBlob(normalized, parsed.header.compression, fileBytes.length);
    }

    /**
     * 将损坏块文件移入隔离目录（加时间戳避免覆盖），保留证据。
     */
    public Path quarantine(String fileName) throws IOException {
        Path source = chunksDir.resolve(fileName);
        if (!Files.exists(source)) {
            return quarantineDir.resolve(fileName);
        }
        String stamped = fileName.replace(FILE_SUFFIX, "")
                + "-" + System.currentTimeMillis() + FILE_SUFFIX;
        Path target = quarantineDir.resolve(stamped);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * 文件目录快照取得后的钩子；默认空实现，测试可覆写以在快照之后、入库之前安排并发写入。
     */
    protected void onSnapshotTaken() {
    }

    /**
     * 扫描块目录：从文件头重建元数据并逐文件校验；不移动任何文件，由调用方决定隔离/审计。
     */
    public List<ScanEntry> scanAll() throws IOException {
        List<ScanEntry> entries = new ArrayList<>();
        List<Path> listed;
        try (Stream<Path> files = Files.list(chunksDir)) {
            listed = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        onSnapshotTaken();
        for (Path file : listed) {
                String fileName = file.getFileName().toString();
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    ParsedFile parsed = parse(bytes, fileName);
                    byte[] normalized = parsed.header.compression == Compression.GZIP
                            ? gunzip(parsed.payload)
                            : parsed.payload;
                    if (!sha256Hex(normalized).equals(parsed.header.checksum)) {
                        entries.add(new ScanBad(fileName, "校验和不匹配"));
                        continue;
                    }
                    List<PointDatum> points = PointCodec.decode(normalized);
                    if (points.size() != parsed.header.pointCount) {
                        entries.add(new ScanBad(fileName, "点数与头部不一致"));
                        continue;
                    }
                    ChunkMetadata md = new ChunkMetadata(
                            parsed.header.chunkId,
                            parsed.header.version,
                            parsed.header.source,
                            parsed.header.startTs,
                            parsed.header.endTs,
                            parsed.header.bbox,
                            parsed.header.pointCount,
                            parsed.header.compression,
                            parsed.header.checksum,
                            fileName,
                            bytes.length,
                            Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()));
                    entries.add(new ScanOk(fileName, md));
                } catch (CorruptChunkException | IOException e) {
                    entries.add(new ScanBad(fileName, e.getMessage() == null ? "文件损坏" : e.getMessage()));
                }
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // 内部：临时文件校验、压缩、解析
    // ------------------------------------------------------------------

    private void verifyTempFile(Path temp, String expectedChecksum,
                                int expectedNormalizedLen, int expectedPayloadLen) throws IOException {
        byte[] written = Files.readAllBytes(temp);
        ParsedFile parsed = parse(written, temp.getFileName().toString());
        if (parsed.payload.length != expectedPayloadLen
                || parsed.header.normalizedLength != expectedNormalizedLen) {
            throw new IOException("临时文件长度校验失败: " + temp);
        }
        byte[] normalized = parsed.header.compression == Compression.GZIP
                ? gunzip(parsed.payload)
                : parsed.payload;
        if (!sha256Hex(normalized).equals(expectedChecksum)) {
            throw new IOException("临时文件校验和不匹配: " + temp);
        }
    }

    private static byte[] tryGzip(byte[] raw) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(16, raw.length / 2));
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(raw);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] payload) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private ParsedFile parse(byte[] bytes, String fileName) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes)))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(MAGIC, magic)) {
                throw new CorruptChunkException("非法文件头: " + fileName);
            }
            byte format = in.readByte();
            if (format != FORMAT_VERSION) {
                throw new CorruptChunkException("不支持的格式版本: " + format);
            }
            ChunkHeader h = new ChunkHeader();
            h.compression = in.readByte() == 1 ? Compression.GZIP : Compression.NONE;
            h.checksumBytes = in.readNBytes(32);
            if (h.checksumBytes.length != 32) {
                throw new CorruptChunkException("校验和字段截断: " + fileName);
            }
            h.checksum = toHex(h.checksumBytes);
            h.chunkId = in.readUTF();
            h.version = in.readLong();
            h.source = in.readUTF();
            h.pointCount = in.readInt();
            h.startTs = in.readLong();
            h.endTs = in.readLong();
            double minX = in.readDouble();
            double minY = in.readDouble();
            double minZ = in.readDouble();
            double maxX = in.readDouble();
            double maxY = in.readDouble();
            double maxZ = in.readDouble();
            h.bbox = new com.example.starter.lidar.model.BBox(minX, minY, minZ, maxX, maxY, maxZ);
            h.normalizedLength = in.readInt();
            h.payloadLength = in.readInt();
            byte[] payload = in.readNBytes(h.payloadLength);
            if (payload.length != h.payloadLength) {
                throw new CorruptChunkException("载荷截断: " + fileName);
            }
            return new ParsedFile(h, payload);
        } catch (CorruptChunkException e) {
            throw e;
        } catch (IOException e) {
            throw new CorruptChunkException("文件解析失败: " + fileName, e);
        }
    }

    /** 将 chunkId 编码为合法且可逆的块文件名。 */
    public static String encodeFileName(String chunkId) {
        // 与 java.net.URLEncoder 相同的安全编码，保证文件名合法且可逆
        StringBuilder sb = new StringBuilder(chunkId.length() + 4);
        for (byte b : chunkId.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb + FILE_SUFFIX;
    }

    /** 计算字节内容的 SHA-256 十六进制小写摘要。 */
    public static String sha256Hex(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            return toHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 文件头内存表示。 */
    private static final class ChunkHeader {
        private Compression compression;
        private String checksum;
        private byte[] checksumBytes;
        private String chunkId;
        private long version;
        private String source;
        private int pointCount;
        private long startTs;
        private long endTs;
        private com.example.starter.lidar.model.BBox bbox;
        private int normalizedLength;
        private int payloadLength;

        static ChunkHeader from(String chunkId, long version, String source,
                                List<PointDatum> points, byte[] normalized,
                                String checksum, Compression requested) {
            ChunkHeader h = new ChunkHeader();
            h.compression = requested;
            h.checksum = checksum;
            h.checksumBytes = fromHex(checksum);
            h.chunkId = chunkId;
            h.version = version;
            h.source = source;
            h.pointCount = points.size();
            h.startTs = points.get(0).t();
            h.endTs = points.get(0).t();
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (PointDatum p : points) {
                h.startTs = Math.min(h.startTs, p.t());
                h.endTs = Math.max(h.endTs, p.t());
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
                maxZ = Math.max(maxZ, p.z());
            }
            h.bbox = new com.example.starter.lidar.model.BBox(minX, minY, minZ, maxX, maxY, maxZ);
            h.normalizedLength = normalized.length;
            return h;
        }

        void write(OutputStream out, byte[] payload, Compression actualCompression) throws IOException {
            this.payloadLength = payload.length;
            this.compression = actualCompression;
            try (DataOutputStream d = new DataOutputStream(out)) {
                d.write(MAGIC);
                d.writeByte(FORMAT_VERSION);
                d.writeByte(actualCompression == Compression.GZIP ? 1 : 0);
                d.write(checksumBytes);
                d.writeUTF(chunkId);
                d.writeLong(version);
                d.writeUTF(source);
                d.writeInt(pointCount);
                d.writeLong(startTs);
                d.writeLong(endTs);
                d.writeDouble(bbox.minX());
                d.writeDouble(bbox.minY());
                d.writeDouble(bbox.minZ());
                d.writeDouble(bbox.maxX());
                d.writeDouble(bbox.maxY());
                d.writeDouble(bbox.maxZ());
                d.writeInt(normalizedLength);
                d.writeInt(payloadLength);
                d.write(payload);
            }
        }

        private static byte[] fromHex(String hex) {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        }
    }

    /** 头部与载荷解析结果。 */
    private record ParsedFile(ChunkHeader header, byte[] payload) {
    }
}
