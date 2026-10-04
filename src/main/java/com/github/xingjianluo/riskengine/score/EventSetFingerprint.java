package com.github.xingjianluo.riskengine.score;

import com.github.xingjianluo.riskengine.model.TransactionEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * 事件集合指纹：对"本次评分所依据的事件集合"计算 SHA-256。
 *
 * <p>集合语义而非序列语义：每条事件先序列化为规范行（字段已摄入时归一化：
 * 地址小写、金额去尾零、资产大写、txHash 小写），所有规范行去重并按字典序
 * 排序后再哈希。因此：</p>
 * <ul>
 *   <li>同一批事件无论以什么顺序到达、重复投递多少次，指纹完全一致；</li>
 *   <li>事件集合只要有增、删、改（任何一个字段变化），指纹必然改变。</li>
 * </ul>
 */
public final class EventSetFingerprint {

    private EventSetFingerprint() {
    }

    /**
     * 计算与指定地址相关的子集指纹：只纳入该地址作为收/付方参与的事件，
     * 即"本次结论实际依据的事件集合"。
     */
    public static String of(String address, List<TransactionEvent> events) {
        TreeSet<String> canonicalLines = new TreeSet<>();
        for (TransactionEvent e : events) {
            if (e.fromAddress().equals(address) || e.toAddress().equals(address)) {
                canonicalLines.add(canonicalLine(e));
            }
        }
        return sha256Hex(String.join("\n", canonicalLines));
    }

    /** 单条事件的规范序列化：字段定界 + 归一化值，保证一字节不差。 */
    static String canonicalLine(TransactionEvent e) {
        return String.join("|",
                e.eventId(),
                e.txHash(),
                e.fromAddress(),
                e.toAddress(),
                e.amount().toPlainString(),
                e.asset(),
                e.timestamp().toString());
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
