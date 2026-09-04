package io.github.jukomu.picacomic.core.internal.crypto;

import io.github.jukomu.picacomic.api.exception.PicaComicException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Random;
import java.util.stream.IntStream;

import static io.github.jukomu.picacomic.core.internal.constant.PicaConstants.*;

/**
 * @author JUKOMU
 * @Description: 内部工具类，负责处理PicaComic API的加密和解密逻辑
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public final class PicaCryptoTool {

    // 解密后的 Key
    private static String cachedApiKey = "C69BAF41DA5ABD1FFEDC6D2FEA56B";
    private static String cachedHmacKey = "~d}$Q7$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn";

    /**
     * 获取解密后的 API Key
     */
    public static synchronized String getApiKey() {
        if (cachedApiKey == null) {
            cachedApiKey = decryptHardcodedKey(ENCRYPTED_API_KEY);
        }
        return cachedApiKey;
    }

    /**
     * 获取解密后的 HMAC Secret Key
     */
    public static synchronized String getHmacKey() {
        if (cachedHmacKey == null) {
            cachedHmacKey = decryptHardcodedKey(ENCRYPTED_HMAC_KEY);
        }
        return cachedHmacKey;
    }

    /**
     * 生成随机 Nonce
     */
    public static String generateNonce() {
        String chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString().toLowerCase();
    }

    /**
     * 生成请求签名
     *
     * @param urlPath   请求路径 (不包含域名，例如 "comics?page=1")
     * @param timestamp 秒级时间戳字符串
     * @param nonce     随机字符串
     * @param method    请求方法 (GET/POST)
     * @return Hex 格式的签名字符串
     */
    public static String generateSignature(String urlPath, String timestamp, String nonce, String method) {
        String apiKey = getApiKey();
        String hmacSecret = getHmacKey();

        // 拼接原始签名串
        // 逻辑：path + timestamp + nonce + method + apiKey
        String raw = urlPath + timestamp + nonce + method + apiKey;

        String payload = raw.toLowerCase();

        try {
            return hmacSha256(payload, hmacSecret);
        } catch (Exception e) {
            throw new PicaComicException("Failed to generate signature", e);
        }
    }

    /**
     * 核心解密逻辑
     * 逻辑: Base64 Decode -> Shuffle (wa) -> XOR -> Base64 Decode
     */
    private static String decryptHardcodedKey(String encryptedBase64) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedBase64);
            String step1 = new String(decodedBytes, StandardCharsets.ISO_8859_1);

            String step2 = permuteString(step1, SHUFFLE_SEED_KEY);

            StringBuilder step3 = new StringBuilder();
            for (char c : step2.toCharArray()) {
                step3.append((char) (c ^ XOR_KEY));
            }

            byte[] finalBytes = Base64.getDecoder().decode(step3.toString());
            return new String(finalBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 字符串混淆/置换逻辑
     */
    private static String permuteString(String input, String key) {
        int length = input.length();
        int[] indices = IntStream.range(0, length).toArray();

        // 计算 Key 的 Hash 种子
        int seed = 0;
        for (char c : key.toCharArray()) {
            seed += c;
        }

        // 伪随机交换算法
        for (int i = indices.length - 1; i > 0; i--) {
            seed = (9301 * seed + 49297) % 233280;
            int targetIndex = seed % (i + 1);

            // 交换索引
            int temp = indices[i];
            indices[i] = indices[targetIndex];
            indices[targetIndex] = temp;
        }

        // 根据打乱后的索引重组字符串
        char[] result = new char[length];
        for (int i = 0; i < length; i++) {
            result[i] = input.charAt(indices[i]);
        }
        return new String(result);
    }

    /**
     * 标准 HMAC-SHA256 实现
     */
    private static String hmacSha256(String data, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256_HMAC = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec secret_key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        sha256_HMAC.init(secret_key);
        byte[] bytes = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));

        // 转换为 Hex 字符串
        StringBuilder hash = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hash.append('0');
            }
            hash.append(hex);
        }
        return hash.toString();
    }
}
