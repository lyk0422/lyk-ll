package com.example.starter.lidar.store;

import com.example.starter.lidar.model.PointDatum;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 点列表的规范化二进制编码（大端序）：
 * pointCount(int) + 每点 [x(double), y(double), z(double), t(long)]。
 * 同一点序列的编码字节确定，SHA-256 校验和即针对该编码计算。
 */
public final class PointCodec {

    private PointCodec() {
    }

    /** 将点序列编码为规范化字节。 */
    public static byte[] encode(List<PointDatum> points) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(points.size());
            for (PointDatum p : points) {
                out.writeDouble(p.x());
                out.writeDouble(p.y());
                out.writeDouble(p.z());
                out.writeLong(p.t());
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("内存编码不应产生 IO 异常", e);
        }
    }

    /** 从规范化字节还原点序列；截断等结构问题按损坏处理。 */
    public static List<PointDatum> decode(byte[] normalized) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new ByteArrayInputStream(normalized)))) {
            int count = in.readInt();
            if (count < 0) {
                throw new CorruptChunkException("规范化内容点数为负");
            }
            List<PointDatum> points = new ArrayList<>(Math.min(count, 1 << 20));
            for (int i = 0; i < count; i++) {
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                long t = in.readLong();
                points.add(new PointDatum(x, y, z, t));
            }
            if (in.read() != -1) {
                throw new CorruptChunkException("规范化内容存在多余字节");
            }
            return points;
        } catch (IOException e) {
            throw new CorruptChunkException("规范化内容解析失败", e);
        }
    }
}
