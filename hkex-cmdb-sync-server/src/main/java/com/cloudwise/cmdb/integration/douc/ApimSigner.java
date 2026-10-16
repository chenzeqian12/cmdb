package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.common.exception.IntegrationException;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * APIM 应用鉴权签名计算。
 *
 * <p>签名原文由四段拼接而成，与服务端
 * {@code apim/apimServer/.../service/ApiService.java:360} 的算法一致：</p>
 *
 * <pre>
 * appSign = hash(appKey + appSecret + timestamp + random)
 * </pre>
 *
 * <p>其中 hash 由服务端配置 {@code apim.sign.mode} 决定，取值 {@code sm3}（默认）、
 * {@code md5}、{@code sha256}，见 {@code com.cloudwise.apim.utils.EncryptionUtil}。</p>
 *
 * <p><b>拼接时不要加任何分隔符</b>，服务端就是直接字符串相加。</p>
 *
 * @author hkex-cmdb-sync
 */
public final class ApimSigner {

    /** 默认签名算法，与服务端 EncryptionUtil 的兜底值一致。 */
    public static final String DEFAULT_MODE = "sm3";

    private static final String MODE_MD5 = "md5";

    private static final String MODE_SHA256 = "sha256";

    private static final String MODE_SM3 = "sm3";

    private ApimSigner() {
    }

    /**
     * 计算签名。
     *
     * @param appKey    APIM 应用的 appKey
     * @param appSecret APIM 应用的 appSecret
     * @param timestamp 毫秒时间戳
     * @param random    随机串（nonce）
     * @param signMode  算法，{@code sm3} / {@code md5} / {@code sha256}，为空按 sm3
     * @return 十六进制小写签名
     */
    public static String sign(String appKey, String appSecret, String timestamp, String random, String signMode) {
        String payload = appKey + appSecret + timestamp + random;
        String mode = signMode == null || signMode.isBlank()
                ? DEFAULT_MODE
                : signMode.trim().toLowerCase(Locale.ROOT);

        return switch (mode) {
            case MODE_MD5 -> digest("MD5", payload);
            case MODE_SHA256 -> digest("SHA-256", payload);
            case MODE_SM3 -> sm3(payload);
            default -> throw new IntegrationException(
                    "Unsupported signature algorithm: douc.sign-mode=" + signMode + ", expected sm3 / md5 / sha256");
        };
    }

    private static String digest(String algorithm, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Hex.toHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IntegrationException("This JVM does not support digest algorithm " + algorithm, e);
        }
    }

    private static String sm3(String payload) {
        SM3Digest sm3 = new SM3Digest();
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        sm3.update(data, 0, data.length);
        byte[] hash = new byte[sm3.getDigestSize()];
        sm3.doFinal(hash, 0);
        return Hex.toHexString(hash);
    }
}
