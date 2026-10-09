package top.smallfox.maiacb.core;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * RIFF WAVE / PCM16 解析，等价复刻 VGAudio.Containers.Wave.WaveReader 的有效性校验与采样统计：
 * SampleCount = data 块大小 / bytesPerSample / 声道数（每声道样本数），SampleRate 取 fmt 块。
 */
public final class WaveFile {

    private static final int WAVE_FORMAT_PCM = 0x0001;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    /** WAVEFORMATEX 标准 16 字节之后的扩展 GUID 子格式，用于 WAVE_FORMAT_EXTENSIBLE。 */
    private static final byte[] MEDIA_SUBTYPE_PCM = {
            (byte) 0x01, 0x00, 0x00, 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x00,
            (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0xAA, (byte) 0x00, (byte) 0x38, (byte) 0x9B, (byte) 0x71
    };

    private final int sampleRate;
    private final int sampleCount;

    private WaveFile(int sampleRate, int sampleCount) {
        this.sampleRate = sampleRate;
        this.sampleCount = sampleCount;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** 每声道样本数。 */
    public int sampleCount() {
        return sampleCount;
    }

    public static WaveFile parse(byte[] wav) {
        if (wav == null || wav.length < 12) {
            throw new IllegalArgumentException("Not a valid WAVE file");
        }
        if (!eq4(wav, 0, 'R', 'I', 'F', 'F')) {
            throw new IllegalArgumentException("Not a valid WAVE file (no RIFF header)");
        }
        if (!eq4(wav, 8, 'W', 'A', 'V', 'E')) {
            throw new IllegalArgumentException("Not a valid WAVE file");
        }

        int formatTag = 0;
        int channelCount = 0;
        int sampleRate = 0;
        int blockAlign = 0;
        int bitsPerSample = 0;
        byte[] subFormat = null;
        long dataSize = -1;

        int p = 12;
        // 全部 RIFF 子块依次扫描
        while (p + 8 <= wav.length) {
            byte[] id = Arrays.copyOfRange(wav, p, p + 4);
            long chunkSize = readUInt32LE(wav, p + 4);
            int dataPos = p + 8;
            long dataEnd = dataPos + chunkSize;

            if (dataEnd > wav.length) {
                break;
            }

            if (eq4(id, 0, 'f', 'm', 't', ' ')) {
                if (dataPos + 16 <= wav.length) {
                    formatTag = readUInt16LE(wav, dataPos);
                    channelCount = readUInt16LE(wav, dataPos + 2);
                    sampleRate = (int) readUInt32LE(wav, dataPos + 4);
                    blockAlign = readUInt16LE(wav, dataPos + 12);
                    bitsPerSample = readUInt16LE(wav, dataPos + 14);
                    // 可选扩展：cbSize + SubFormat GUID
                    if (dataPos + 18 <= wav.length && formatTag == WAVE_FORMAT_EXTENSIBLE) {
                        int cbSize = readUInt16LE(wav, dataPos + 16);
                        if (cbSize >= 16 && dataPos + 18 + 16 <= wav.length) {
                            subFormat = Arrays.copyOfRange(wav, dataPos + 18, dataPos + 18 + 16);
                        }
                    }
                }
            } else if (eq4(id, 0, 'd', 'a', 't', 'a')) {
                dataSize = chunkSize;
            }

            // 奇数长度子块补 1 字节对齐
            p = (int) (dataEnd + (chunkSize % 2 == 1 ? 1 : 0));
        }

        if (formatTag != WAVE_FORMAT_PCM && formatTag != WAVE_FORMAT_EXTENSIBLE) {
            throw new IllegalArgumentException("Must contain PCM data. Has unsupported format " + formatTag);
        }
        if (bitsPerSample != 16 && bitsPerSample != 8) {
            throw new IllegalArgumentException("Must have 8 or 16 bits per sample, not " + bitsPerSample + " bits per sample");
        }
        if (channelCount == 0) {
            throw new IllegalArgumentException("Channel count must not be zero");
        }
        int bytesPerSample = (bitsPerSample + 7) / 8;
        if (blockAlign != bytesPerSample * channelCount) {
            throw new IllegalArgumentException("File has invalid block alignment");
        }
        if (formatTag == WAVE_FORMAT_EXTENSIBLE && subFormat == null) {
            throw new IllegalArgumentException("Must contain PCM data. Has unsupported SubFormat");
        }
        if (formatTag == WAVE_FORMAT_EXTENSIBLE && !Arrays.equals(subFormat, MEDIA_SUBTYPE_PCM)) {
            throw new IllegalArgumentException("Must contain PCM data. Has unsupported SubFormat " + hex(subFormat));
        }
        if (dataSize < 0) {
            throw new IllegalArgumentException("File must have a valid data chunk");
        }

        long sampleCount = dataSize / bytesPerSample / channelCount;
        return new WaveFile(sampleRate, (int) sampleCount);
    }

    private static boolean eq4(byte[] b, int off, int c0, int c1, int c2, int c3) {
        return b[off] == c0 && b[off + 1] == c1 && b[off + 2] == c2 && b[off + 3] == c3;
    }

    private static boolean eq4(byte[] b, int off, byte[] cmp) {
        return cmp.length >= 4 && b[off] == cmp[0] && b[off + 1] == cmp[1] && b[off + 2] == cmp[2] && b[off + 3] == cmp[3];
    }

    private static int readUInt16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static long readUInt32LE(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | (b[off + 1] & 0xFFL) << 8
                | (b[off + 2] & 0xFFL) << 16
                | (b[off + 3] & 0xFFL) << 24;
    }

    private static String hex(byte[] b) {
        if (b == null) {
            return "null";
        }
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02X", x));
        }
        return s.toString();
    }
}