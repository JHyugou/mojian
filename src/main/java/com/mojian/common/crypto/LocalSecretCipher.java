package com.mojian.common.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import javax.crypto.BadPaddingException;

/**
 * 本机密钥加密组件（AES-256-GCM），用于 API Key 落库前加密。
 *
 * 密钥来源：{user.home}/.mojian/llm-secret.key，首次使用时自动生成 32 字节随机密钥。
 * 密钥不入 git、不进数据库，仅留在本机用户目录——换机器或删除该文件后旧密文不可解，
 * 属预期行为（在设置页重新保存 Key 即可恢复）。
 *
 * 解密失败按"未配置"处理（返回 null）而不是抛异常，避免历史脏数据阻断创作主流程。
 */
@Slf4j
@Component
public class LocalSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;

    public LocalSecretCipher() {
        this.key = loadOrCreateKey();
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** 解密失败返回 null 并告警：密文损坏或本机密钥被更换时按"未配置密钥"对待 */
    public String decryptSafe(String storedBase64) {
        if (storedBase64 == null || storedBase64.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(storedBase64);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (BadPaddingException e) {
            log.warn("API Key 密文校验失败（本机密钥可能被更换），按未配置处理");
            return null;
        } catch (Exception e) {
            log.warn("API Key 密文解析失败: {}", e.getMessage());
            return null;
        }
    }

    private SecretKey loadOrCreateKey() {
        Path file = Path.of(System.getProperty("user.home"), ".mojian", "llm-secret.key");
        try {
            if (Files.exists(file)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(file).trim());
                return new SecretKeySpec(raw, ALGORITHM);
            }
            byte[] raw = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(raw);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(raw));
            restrictToOwner(file);
            log.info("已生成本机加密密钥文件: {}", file);
            return new SecretKeySpec(raw, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化本机加密密钥文件: " + file, e);
        }
    }

    /** POSIX 系统上把密钥文件收敛为仅当前用户可读写；Windows 无此语义则忽略 */
    private void restrictToOwner(Path file) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view != null) {
                Set<java.nio.file.attribute.PosixFilePermission> ownerOnly =
                        PosixFilePermissions.fromString("rw-------");
                view.setPermissions(ownerOnly);
            }
        } catch (Exception ignored) {
            // 非 POSIX 文件系统（如 Windows NTFS）没有这套权限模型
        }
    }
}
