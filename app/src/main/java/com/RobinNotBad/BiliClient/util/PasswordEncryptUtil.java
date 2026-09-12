package com.RobinNotBad.BiliClient.util;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * 登录密码加密工具。
 * 与B站网页端算法一致：先拼接服务端下发的hash作为前缀，再用服务端下发的RSA公钥做PKCS1填充加密，最后Base64编码。
 * 明文密码只在内存中短暂存在，不落盘。
 */
public class PasswordEncryptUtil {

    //去掉PEM头尾与换行，只保留Base64主体
    public static String getKeyPem(String rawKey) {
        String content = rawKey.trim();
        content = content.replace("-----BEGIN PUBLIC KEY-----", "");
        content = content.replace("-----END PUBLIC KEY-----", "");
        content = content.replace("\\n", "").replace("\\r", "").trim();
        return content;
    }

    public static String encryptPassword(String password, String hash, String pubKeyPem) throws Exception {
        String keyContent = getKeyPem(pubKeyPem);
        byte[] keyBytes = Base64.decode(keyContent, Base64.DEFAULT);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey publicKey = keyFactory.generatePublic(keySpec);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedBytes = cipher.doFinal((hash + password).getBytes("UTF-8"));
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
    }
}
