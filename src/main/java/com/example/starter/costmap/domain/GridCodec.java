package com.example.starter.costmap.domain;

import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 行优先栅格数组与 BLOB 之间的编解码（大端序，与平台无关）。
 */
public final class GridCodec {

    private GridCodec() {
    }

    /** int 栅格编码为 4 字节大端数组。 */
    public static byte[] encodeInts(int[] cells) {
        ByteBuffer buffer = ByteBuffer.allocate(cells.length * Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.asIntBuffer().put(cells);
        return buffer.array();
    }

    /** BLOB 解码为 int 栅格，并校验长度。 */
    public static int[] decodeInts(byte[] data, int expectedLength) {
        if (data == null || data.length != expectedLength * Integer.BYTES) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "corrupt grid blob: expected " + expectedLength + " ints");
        }
        int[] cells = new int[expectedLength];
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(cells);
        return cells;
    }

    /** long 栅格编码为 8 字节大端数组。 */
    public static byte[] encodeLongs(long[] cells) {
        ByteBuffer buffer = ByteBuffer.allocate(cells.length * Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.asLongBuffer().put(cells);
        return buffer.array();
    }

    /** BLOB 解码为 long 栅格，并校验长度。 */
    public static long[] decodeLongs(byte[] data, int expectedLength) {
        if (data == null || data.length != expectedLength * Long.BYTES) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "corrupt winner blob: expected " + expectedLength + " longs");
        }
        long[] cells = new long[expectedLength];
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asLongBuffer().get(cells);
        return cells;
    }
}
