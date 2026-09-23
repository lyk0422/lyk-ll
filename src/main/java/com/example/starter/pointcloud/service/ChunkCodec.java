package com.example.starter.pointcloud.service;

import com.example.starter.pointcloud.model.Compression;
import com.example.starter.pointcloud.model.Point;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 块文件二进制编解码。
 *
 * <p>文件布局：{@code magic(4B='L','P','C','1') + compressionFlag(1B) + payload + checksum(32B)}，
 * 其中 {@code payload} 为规范化未压缩内容本身（NONE）或其 GZIP 结果（GZIP），
 * 末尾 32 字节为规范化未压缩内容的 SHA-256。</p>
 *
 * <p>规范化内容布局：{@code chunkIdLen(int BE) + chunkId UTF-8 + version(long BE)
 * + sourceLen(int BE) + source UTF-8 + pointCount(int BE)
 * + 每点 x/y/z double(BE) + timestamp long(BE)}。块文件自描述 chunkId，
 * 以便索引损坏后仅依赖块文件即可重建。</p>
 */
public final class ChunkCodec {

    private static final byte[] MAGIC = {'L', 'P', 'C', '1'};
    private static final byte FLAG_NONE = 0;
    private static final byte FLAG_GZIP = 1;
    private static final int CHECKSUM_LEN = 32;
    private static final int HEADER_LEN = 5;
    private static final int POINT_BYTES = 32;

    private ChunkCodec() {
    }

    /**
     * 编码结果。
     *
     * @param fileBytes   完整文件字节
     * @param compression 实际使用的压缩方式
     * @param checksum    规范化内容的 SHA-256 十六进制
     */
    public record Encoded(byte[] fileBytes, Compression compression, String checksum) {
    }

    /**
     * 解码结果。
     */
    public record Decoded(String chunkId, long version, String source,
                          List<Point> points, String checksum) {
    }

    /**
     * 规范化并编码；GZIP 失败或压缩后不小于原数据时回退 NONE，内容与校验和保持不变。
     */
    public static Encoded encode(String chunkId, long version, String source,
                                 List<Point> points, Compression requested) {
        return encode(chunkId, version, source, points, requested, ChunkCodec::tryGzip);
    }

    /**
     * 可注入 GZIP 实现的编码入口，供测试压缩失败回退分支。
     */
    static Encoded encode(String chunkId, long version, String source,
                          List<Point> points, Compression requested,
                          java.util.function.Function<byte[], byte[]> gzipFn) {
        byte[] canonical = canonical(chunkId, version, source, points);
        byte[] checksumBytes = digest(canonical);

        byte flag = FLAG_NONE;
        byte[] payload = canonical;
        Compression actual = Compression.NONE;
        if (requested == Compression.GZIP) {
            byte[] gzipped;
            try {
                gzipped = gzipFn.apply(canonical);
            } catch (RuntimeException e) {
                gzipped = null;
            }
            if (gzipped != null && gzipped.length < canonical.length) {
                flag = FLAG_GZIP;
                payload = gzipped;
                actual = Compression.GZIP;
            }
        }

        ByteBuffer out = ByteBuffer.allocate(HEADER_LEN + payload.length + CHECKSUM_LEN);
        out.put(MAGIC);
        out.put(flag);
        out.put(payload);
        out.put(checksumBytes);
        return new Encoded(out.array(), actual, HexFormat.of().formatHex(checksumBytes));
    }

    /**
     * 解码并严格校验魔数、长度、GZIP、SHA-256 与内容结构；任何不一致抛出
     * {@link CorruptChunkException}。
     */
    public static Decoded decode(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < HEADER_LEN + CHECKSUM_LEN) {
            throw new CorruptChunkException("块文件长度不足");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (fileBytes[i] != MAGIC[i]) {
                throw new CorruptChunkException("块文件魔数不匹配");
            }
        }
        byte flag = fileBytes[4];
        if (flag != FLAG_NONE && flag != FLAG_GZIP) {
            throw new CorruptChunkException("未知压缩标记: " + flag);
        }
        int payloadLen = fileBytes.length - HEADER_LEN - CHECKSUM_LEN;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(fileBytes, HEADER_LEN, payload, 0, payloadLen);
        byte[] expected = new byte[CHECKSUM_LEN];
        System.arraycopy(fileBytes, HEADER_LEN + payloadLen, expected, 0, CHECKSUM_LEN);

        byte[] canonical;
        if (flag == FLAG_GZIP) {
            try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(payload))) {
                canonical = in.readAllBytes();
            } catch (IOException e) {
                throw new CorruptChunkException("GZIP 解压失败", e);
            }
        } else {
            canonical = payload;
        }

        byte[] actual = digest(canonical);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new CorruptChunkException("SHA-256 校验失败");
        }

        return parseCanonical(canonical, HexFormat.of().formatHex(actual));
    }

    /**
     * 构造规范化未压缩内容。
     */
    static byte[] canonical(String chunkId, long version, String source, List<Point> points) {
        byte[] id = chunkId.getBytes(StandardCharsets.UTF_8);
        byte[] src = source.getBytes(StandardCharsets.UTF_8);
        long size = 4L + id.length + 8L + 4L + src.length + 4L + points.size() * (long) POINT_BYTES;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("块内容过大");
        }
        ByteBuffer buf = ByteBuffer.allocate((int) size);
        buf.putInt(id.length);
        buf.put(id);
        buf.putLong(version);
        buf.putInt(src.length);
        buf.put(src);
        buf.putInt(points.size());
        for (Point p : points) {
            buf.putDouble(p.x());
            buf.putDouble(p.y());
            buf.putDouble(p.z());
            buf.putLong(p.timestampNanos());
        }
        return buf.array();
    }

    /**
     * 解析规范化内容，结构非法一律视为损坏。
     */
    private static Decoded parseCanonical(byte[] canonical, String checksum) {
        ByteBuffer buf = ByteBuffer.wrap(canonical);
        String chunkId = readText(buf, "chunkId");
        if (buf.remaining() < 8) {
            throw new CorruptChunkException("规范化内容缺少 version");
        }
        long version = buf.getLong();
        String source = readText(buf, "source");
        if (buf.remaining() < 4) {
            throw new CorruptChunkException("规范化内容缺少 pointCount");
        }
        int pointCount = buf.getInt();
        if (pointCount < 0 || buf.remaining() != pointCount * (long) POINT_BYTES) {
            throw new CorruptChunkException("点数与剩余字节不一致");
        }
        List<Point> points = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            points.add(new Point(buf.getDouble(), buf.getDouble(), buf.getDouble(), buf.getLong()));
        }
        return new Decoded(chunkId, version, source, points, checksum);
    }

    /**
     * 读取 4 字节长度前缀的 UTF-8 文本字段。
     */
    private static String readText(ByteBuffer buf, String field) {
        if (buf.remaining() < 4) {
            throw new CorruptChunkException("规范化内容缺少 " + field + " 长度");
        }
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            throw new CorruptChunkException(field + " 长度越界");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 尝试 GZIP；任何异常返回 {@code null} 表示压缩失败。
     */
    static byte[] tryGzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            gzipInto(out, data);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return out.toByteArray();
    }

    /**
     * 将数据 GZIP 写入给定输出流；包内可见以便测试注入会失败的输出流。
     */
    static void gzipInto(OutputStream out, byte[] data) throws IOException {
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
    }

    static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(digest(data));
    }
}
