package com.mojian.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本机密钥加密组件纯函数单测：加解密往返、错密文 resilience、掩码不适用（见 LlmSettingsService.mask）。
 */
class LocalSecretCipherTest {

    private final LocalSecretCipher cipher = new LocalSecretCipher();

    @Test
    void encryptDecryptRoundTrip() {
        String key = "sk-test-abcdefghijklmnopqrstuvwxyz-1234567890";
        String stored = cipher.encrypt(key);
        assertNotEquals(key, stored, "落库必须是密文");
        assertEquals(key, cipher.decryptSafe(stored));
    }

    @Test
    void samePlaintextProducesDifferentCiphertexts() {
        String plain = "sk-random-secret";
        assertNotEquals(cipher.encrypt(plain), cipher.encrypt(plain),
                "随机 IV 下同一明文每次密文应不同");
    }

    @Test
    void corruptCiphertextReturnsNullInsteadOfThrowing() {
        String valid = cipher.encrypt("sk-something");
        String corrupted = "AA" + valid.substring(2);
        assertNull(cipher.decryptSafe(corrupted), "损坏密文按未配置处理，不阻断主流程");
        assertNull(cipher.decryptSafe("not-base64!!!"));
        assertNull(cipher.decryptSafe(null));
        assertNull(cipher.decryptSafe(" "));
    }
}
