package com.example.mangadedupe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 文件头解析测试：扫描加速的核心是"不解码整图也能拿到宽高"，
 * 这里用人工构造的最小文件头验证 JPEG / PNG / GIF / WebP 四种格式。
 */
public class ImgHeadTest {

    @Test
    public void png() {
        byte[] b = new byte[40];
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        b[4] = 0x0D;
        b[5] = 0x0A;
        b[6] = 0x1A;
        b[7] = 0x0A;
        // IHDR: 长度(4) 类型(4) 宽(4) 高(4)
        b[8] = 0;
        b[9] = 0;
        b[10] = 0;
        b[11] = 13;
        b[12] = 'I';
        b[13] = 'H';
        b[14] = 'D';
        b[15] = 'R';
        putBe32(b, 16, 1200);
        putBe32(b, 20, 1700);
        int[] wh = ImgHead.size(b, b.length);
        assertEquals(1200, wh[0]);
        assertEquals(1700, wh[1]);
    }

    @Test
    public void jpeg() {
        byte[] b = new byte[48];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        // APP0，长度 0x0010 = 16 → 跳过 18 字节，到偏移 20
        b[2] = (byte) 0xFF;
        b[3] = (byte) 0xE0;
        b[4] = 0x00;
        b[5] = 0x10;
        // SOF0 在 20
        b[20] = (byte) 0xFF;
        b[21] = (byte) 0xC0;
        b[22] = 0x00;
        b[23] = 0x11;
        b[24] = 0x08;                 // 精度
        putBe16(b, 25, 1700);         // 高
        putBe16(b, 27, 1200);         // 宽
        int[] wh = ImgHead.size(b, b.length);
        assertEquals(1200, wh[0]);
        assertEquals(1700, wh[1]);
    }

    @Test
    public void gif() {
        byte[] b = new byte[32];
        b[0] = 'G';
        b[1] = 'I';
        b[2] = 'F';
        b[3] = '8';
        b[4] = '9';
        b[5] = 'a';
        putLe16(b, 6, 640);
        putLe16(b, 8, 480);
        int[] wh = ImgHead.size(b, b.length);
        assertEquals(640, wh[0]);
        assertEquals(480, wh[1]);
    }

    @Test
    public void webpVp8x() {
        byte[] b = new byte[40];
        b[0] = 'R';
        b[1] = 'I';
        b[2] = 'F';
        b[3] = 'F';
        b[8] = 'W';
        b[9] = 'E';
        b[10] = 'B';
        b[11] = 'P';
        b[12] = 'V';
        b[13] = 'P';
        b[14] = '8';
        b[15] = 'X';
        // 24 位小端画布尺寸 - 1
        int w = 900;
        int h = 1200;
        b[24] = (byte) ((w - 1) & 0xFF);
        b[25] = (byte) (((w - 1) >> 8) & 0xFF);
        b[26] = (byte) (((w - 1) >> 16) & 0xFF);
        b[27] = (byte) ((h - 1) & 0xFF);
        b[28] = (byte) (((h - 1) >> 8) & 0xFF);
        b[29] = (byte) (((h - 1) >> 16) & 0xFF);
        int[] wh = ImgHead.size(b, b.length);
        assertEquals(w, wh[0]);
        assertEquals(h, wh[1]);
    }

    @Test
    public void unknownReturnsZero() {
        byte[] b = new byte[64];
        int[] wh = ImgHead.size(b, b.length);
        assertEquals(0, wh[0]);
        assertEquals(0, wh[1]);
        int[] nullSafe = ImgHead.size(null, 0);
        assertEquals(0, nullSafe[0]);
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static void putBe16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
