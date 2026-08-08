package com.fourth.ykd.ai.memory.index;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 把原始微信 userId 转成一个固定长度、只包含十六进制字符的字符串，专门给 Redis TAG 过滤使用。
 * 为 Redis 长期记忆生成稳定且可安全过滤的用户范围标识。
 * 把原始用户 ID 转换成适合 Redis TAG 查询的用户范围标识
 */
public final class RedisMemoryUserScope {

    //私有：不允许创建对象
    private RedisMemoryUserScope() {
    }

    /**
     * 将微信用户 ID 转换为只包含十六进制字符的固定长度标识。
     * @param userId 原始微信用户 ID
     * @return 可直接用于 Redis TAG 过滤的用户范围标识
     */
    public static String fromUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }

        try {
            //获取 SHA-256 算法对象
            byte[] digest = MessageDigest.getInstance("SHA-256")
            /*算哈希返回byte[]
            String
               ↓
            UTF-8 byte[]
               ↓
            SHA-256*/
            .digest(userId.trim().getBytes(StandardCharsets.UTF_8));
            //转换为十六进制字符串
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }
}
