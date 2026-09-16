package com.orinan.db.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public class AesGcmStringEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";

    /*
     * GCM 권장 nonce 크기
     */
    private static final int IV_LENGTH_BYTES = 12;

    /*
     * GCM 인증 태그 크기
     */
    private static final int TAG_LENGTH_BITS = 128;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

    /*
     * 암호문 버전.
     * 추후 암호화 키나 저장 형식을 변경할 때 구분하기 위한 값입니다.
     */
    private static final String VERSION_PREFIX = "v1:";

    /*
     * 해당 애플리케이션의 개인정보 암호문이라는 것을 검증하기 위한 부가 데이터.
     * DB에 저장되지는 않지만 암호화와 복호화 때 같은 값을 사용해야 합니다.
     */
    private static final byte[] AAD =
            "orinan:personal-data:v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey secretKey;

    public AesGcmStringEncryptor(CryptoProperties properties) {
        this.secretKey = createSecretKey(properties.personalDataKey());
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }

        try {
            byte[] iv = generateIv();

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            cipher.updateAAD(AAD);

            /*
             * GCM의 doFinal 결과에는 암호문과 인증 태그가 함께 포함됩니다.
             */
            byte[] encryptedBytes = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8)
            );

            ByteBuffer buffer = ByteBuffer.allocate(
                    IV_LENGTH_BYTES + encryptedBytes.length
            );

            buffer.put(iv);
            buffer.put(encryptedBytes);

            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(buffer.array());

            return VERSION_PREFIX + encoded;

        } catch (GeneralSecurityException e) {
            throw new DataCryptoException(
                    "개인정보 암호화에 실패했습니다.",
                    e
            );
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) {
            return null;
        }

        if (!encryptedText.startsWith(VERSION_PREFIX)) {
            throw new DataCryptoException(
                    "지원하지 않는 개인정보 암호문 형식입니다."
            );
        }

        try {
            String encoded = encryptedText.substring(
                    VERSION_PREFIX.length()
            );

            byte[] combinedBytes = Base64.getUrlDecoder().decode(encoded);

            validateEncryptedDataLength(combinedBytes);

            ByteBuffer buffer = ByteBuffer.wrap(combinedBytes);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);

            byte[] cipherTextWithTag = new byte[buffer.remaining()];
            buffer.get(cipherTextWithTag);

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            cipher.updateAAD(AAD);

            byte[] decryptedBytes = cipher.doFinal(cipherTextWithTag);

            return new String(
                    decryptedBytes,
                    StandardCharsets.UTF_8
            );

        } catch (IllegalArgumentException e) {
            throw new DataCryptoException(
                    "개인정보 암호문의 Base64 형식이 올바르지 않습니다.",
                    e
            );

        } catch (GeneralSecurityException e) {
            /*
             * 키가 다르거나 암호문이 변조된 경우에도 여기로 들어옵니다.
             */
            throw new DataCryptoException(
                    "개인정보 복호화에 실패했습니다. 키 또는 암호문을 확인해주세요.",
                    e
            );
        }
    }

    private SecretKey createSecretKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException(
                    "orinan.crypto.personal-data-key 설정이 필요합니다."
            );
        }

        final byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "개인정보 암호화 키가 올바른 Base64 형식이 아닙니다.",
                    e
            );
        }

        /*
         * AES-256은 정확히 32바이트 키를 사용합니다.
         */
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "개인정보 암호화 키는 Base64 디코딩 후 " +
                            "32바이트여야 합니다. 현재: " +
                            keyBytes.length +
                            "바이트"
            );
        }

        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    private void validateEncryptedDataLength(byte[] combinedBytes) {
        /*
         * IV 12바이트 + GCM 인증 태그 16바이트보다 커야 합니다.
         */
        int minimumLength = IV_LENGTH_BYTES + TAG_LENGTH_BYTES;

        if (combinedBytes.length < minimumLength) {
            throw new DataCryptoException(
                    "개인정보 암호문 길이가 올바르지 않습니다."
            );
        }
    }
}