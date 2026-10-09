package top.smallfox.maiacb.core.cri;

/**
 * CRIMW 表的异或掩码算法，对应 SonicAudioLib.CriMw.CriTableMasker。
 * <p>
 * 若文件的 4 字节签名不是 {@code "@UTF"}，则找出 {xor, xorMultiplier}，
 * 从头对整个表做 {@code b ^= xor; xor *= xorMultiplier}（uint32 溢出环绕）还原。
 */
public final class CriTableMasker {

    public static final byte[] SIGNATURE = {'@', 'U', 'T', 'F'};

    private CriTableMasker() {
    }

    /**
     * 从被掩码的 4 字节签名反推 {xor, xorMultiplier}。
     *
     * @return 长度为 2 的数组：[xor, xorMultiplier]
     */
    public static long[] findKeys(byte[] signature) {
        if (signature.length < 4) {
            throw new IllegalArgumentException("'@UTF' signature could not be found. Your file might be corrupted.");
        }
        for (int x = 0; x < 0x100; x++) {
            if ((signature[0] & 0xFF ^ x) == SIGNATURE[0]) {
                for (int m = 0; m < 0x100; m++) {
                    int _x = (x * m) & 0xFF;
                    if ((signature[1] & 0xFF ^ _x) == SIGNATURE[1]) {
                        boolean allMatches = true;
                        for (int i = 2; i < 4; i++) {
                            _x = (_x * m) & 0xFF;
                            if ((signature[i] & 0xFF ^ _x) != SIGNATURE[i]) {
                                allMatches = false;
                                break;
                            }
                        }
                        if (allMatches) {
                            return new long[]{x, m};
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("'@UTF' signature could not be found. Your file might be corrupted.");
    }

    /** 掩码 / 还原 length 字节，返回新的字节数组。 */
    public static byte[] mask(byte[] source, long length, long xor, long xorMultiplier) {
        byte[] result = new byte[(int) length];
        long currentXor = xor;
        for (int i = 0; i < (int) length; i++) {
            result[i] = (byte) (source[i] ^ (byte) (currentXor & 0xFF));
            currentXor = (currentXor * xorMultiplier) & 0xFFFFFFFFL;
        }
        return result;
    }
}