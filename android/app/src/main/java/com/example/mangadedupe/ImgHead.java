package com.example.mangadedupe;

/**
 * 从图片文件头直接解析宽高，**不需要解码整图**。
 * 支持 JPEG / PNG / GIF / WebP，失败返回 {0,0}。
 *
 * 为什么要它：BitmapFactory 量宽高也要开一次流 + 走一遍解码器（几毫秒），
 * 而扫描上万个文件时代价很高。文件头解析是纯内存操作（微秒级）。
 */
public final class ImgHead {

    private ImgHead() {
    }

    /** @return {width, height}；无法识别返回 {0,0} */
    public static int[] size(byte[] b, int len) {
        if (b == null || len < 24) return new int[]{0, 0};
        try {
            // PNG: 89 50 4E 47 0D 0A 1A 0A，IHDR 宽高在 16..23
            if (u(b[0]) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                int w = be32(b, 16);
                int h = be32(b, 20);
                if (w > 0 && h > 0) return new int[]{w, h};
            }
            // GIF: GIF87a / GIF89a，宽高 6..9 小端
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
                int w = le16(b, 6);
                int h = le16(b, 8);
                if (w > 0 && h > 0) return new int[]{w, h};
            }
            // JPEG
            if (u(b[0]) == 0xFF && u(b[1]) == 0xD8) {
                return jpeg(b, len);
            }
            // WebP: RIFF....WEBP
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return webp(b, len);
            }
        } catch (Exception ignored) {
        }
        return new int[]{0, 0};
    }

    private static int[] jpeg(byte[] b, int len) {
        int i = 2;
        while (i + 9 < len) {
            if (u(b[i]) != 0xFF) {
                i++;
                continue;
            }
            int m = u(b[i + 1]);
            // 独立标记（无长度字段）
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                i += 2;
                continue;
            }
            if (m == 0xD9 || m == 0xDA) break;      // EOI / SOS：后面是压缩数据
            int segLen = be16(b, i + 2);
            if (segLen < 2) break;
            boolean isSof = (m >= 0xC0 && m <= 0xC3) || (m >= 0xC5 && m <= 0xC7)
                    || (m >= 0xC9 && m <= 0xCB) || (m >= 0xCD && m <= 0xCF);
            if (isSof && i + 8 < len) {
                int h = be16(b, i + 5);
                int w = be16(b, i + 7);
                if (w > 0 && h > 0) return new int[]{w, h};
                break;
            }
            i += 2 + segLen;
        }
        return new int[]{0, 0};
    }

    private static int[] webp(byte[] b, int len) {
        if (len < 30) return new int[]{0, 0};
        String chunk = new String(new char[]{(char) b[12], (char) b[13], (char) b[14], (char) b[15]});
        if ("VP8X".equals(chunk)) {
            int w = 1 + (u(b[24]) | (u(b[25]) << 8) | (u(b[26]) << 16));
            int h = 1 + (u(b[27]) | (u(b[28]) << 8) | (u(b[29]) << 16));
            if (w > 0 && h > 0) return new int[]{w, h};
        } else if ("VP8 ".equals(chunk)) {
            // 帧头：3 字节起始码 + 2 字节宽 + 2 字节高（各 14 位有效）
            int w = (u(b[26]) | (u(b[27]) << 8)) & 0x3FFF;
            int h = (u(b[28]) | (u(b[29]) << 8)) & 0x3FFF;
            if (w > 0 && h > 0) return new int[]{w, h};
        } else if ("VP8L".equals(chunk)) {
            if (len < 25) return new int[]{0, 0};
            int bits = u(b[21]) | (u(b[22]) << 8) | (u(b[23]) << 16) | (u(b[24]) << 24);
            int w = (bits & 0x3FFF) + 1;
            int h = ((bits >> 14) & 0x3FFF) + 1;
            if (w > 0 && h > 0) return new int[]{w, h};
        }
        return new int[]{0, 0};
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    private static int be16(byte[] b, int i) {
        return (u(b[i]) << 8) | u(b[i + 1]);
    }

    private static int be32(byte[] b, int i) {
        return (u(b[i]) << 24) | (u(b[i + 1]) << 16) | (u(b[i + 2]) << 8) | u(b[i + 3]);
    }

    private static int le16(byte[] b, int i) {
        return u(b[i]) | (u(b[i + 1]) << 8);
    }
}
