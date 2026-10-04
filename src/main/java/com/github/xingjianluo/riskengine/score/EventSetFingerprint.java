package com.github.xingjianluo.riskengine.score;

import com.github.xingjianluo.riskengine.model.TransactionEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 事件集合指纹：对一组已接受事件计算 SHA-256。
 *
 * <p>输入事件必须先按 (timestamp, eventId) 确定性排序（{@code EventStore.snapshot()}
 * 的既有语义），每条事件序列化为
 * {@code eventId|txHash|from|to|amount|asset|timestamp} 一行后拼接哈希。
 * 因此同一批事件无论到达顺序、重复投递次数如何，指纹完全一致；
 * 事件集合有任何增删改，指纹必然改变。</p>
 */
public final class EventSetFingerprint {

    private EventSetFingerprint() {
    }

    public static String of(List<TransactionEvent> events) {
        StringBuilder canonical = new StringBuilder();
        for (TransactionEvent e : events) {
            canonical.append(e.eventId()).append('|')
                    .append(e.txHash()).append('|')
                    .append(e.fromAddress()).append('|')
                    .append(e.toAddress()).append('|')
                    .append(e.amount().toPlainString()).append('|')
                    .append(e.asset()).append('|')
                    .append(e.timestamp()).append('\n');
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
