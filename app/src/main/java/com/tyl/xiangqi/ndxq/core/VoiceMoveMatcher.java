package com.tyl.xiangqi.ndxq.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音走棋匹配器：把语音识别出的文本匹配到当前局面的合法着法上。
 *
 * <p>识别引擎输出的中文常有错别字（“码八进七”“泡二平舞”），按字形比较几乎必败。
 * 这里把识别文本与每个合法着法的中文记谱都转成<b>拼音串</b>再做编辑距离匹配：
 * 同音、近音（z/zh、c/ch、s/sh、l/n、ang/an 等）都能命中，从而把识别结果
 * <b>纠正为标准记谱</b>返回给调用方展示。</p>
 *
 * <p>流程：识别文本 → 归一化（数字/全角/别名）→ 拼音化 → 与全部合法着法的
 * 记谱拼音做加权编辑距离 → 返回按相似度排序的候选。</p>
 */
public final class VoiceMoveMatcher {

    // ==================== 拼音表（覆盖象棋记谱涉及的字） ====================

    private static final Map<Character, String> PINYIN = new HashMap<>();

    static {
        // 棋子
        put("车", "ju"); put("車", "ju"); put("砗", "ju"); put("居", "ju"); put("驹", "ju"); put("桔", "ju");
        put("马", "ma"); put("馬", "ma"); put("码", "ma"); put("玛", "ma");
        put("相", "xiang"); put("象", "xiang"); put("箱", "xiang");
        put("仕", "shi"); put("士", "shi"); put("事", "shi"); put("是", "shi"); put("四", "si"); put("使", "shi");
        put("帅", "shuai"); put("率", "shuai"); put("甩", "shuai");
        put("将", "jiang"); put("酱", "jiang"); put("蒋", "jiang"); put("江", "jiang"); put("讲", "jiang");
        put("炮", "pao"); put("砲", "pao"); put("泡", "pao"); put("袍", "pao"); put("跑", "pao"); put("抛", "pao");
        put("兵", "bing"); put("冰", "bing"); put("丙", "bing");
        put("卒", "zu"); put("足", "zu"); put("族", "zu"); put("组", "zu"); put("祖", "zu");
        put("红", "hong"); put("黑", "hei");
        put("前", "qian"); put("钱", "qian"); put("千", "qian"); put("牵", "qian"); put("签", "qian");
        put("后", "hou"); put("候", "hou"); put("厚", "hou");
        put("中", "zhong"); put("忠", "zhong"); put("钟", "zhong"); put("终", "zhong"); put("重", "zhong"); put("众", "zhong");
        // 动作
        put("进", "jin"); put("晋", "jin"); put("近", "jin"); put("尽", "jin");
        put("退", "tui"); put("腿", "tui"); put("推", "tui"); put("队", "dui"); put("对", "dui");
        put("平", "ping"); put("评", "ping"); put("瓶", "ping"); put("凭", "ping"); put("苹", "ping"); put("乒", "ping");
        put("回", "hui"); put("悔", "hui");
        // 数字：中文、大写、常见同音
        putNum("ling", "零〇");
        putNum("yi", "一二壹医衣以已");
        putNum("er", "二贰两而尔耳");
        putNum("san", "三仨叁散");
        putNum("si", "四肆死似");
        putNum("wu", "五伍无午舞武雾乌屋污诬");
        putNum("liu", "六陆柳");
        putNum("qi", "七柒期其奇气器骑");
        putNum("ba", "八捌扒把爸吧罢拔");
        putNum("jiu", "九玖酒旧就");
        putNum("shi2", "十拾时石实识");
    }

    private static void put(String key, String pinyin) {
        PINYIN.put(key.charAt(0), pinyin);
    }

    /** 把同一读音挂到多个字上（数字组用）。 */
    private static void putNum(String pinyin, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            PINYIN.put(chars.charAt(i), pinyin);
        }
    }

    /** 记谱用中文数字，供 normalize 输出统一汉字。 */
    private static final String[] CN_DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private VoiceMoveMatcher() {}

    /**
     * 在当前局面下把识别文本匹配成合法着法候选。
     *
     * @param board 走子前棋盘（棋子尚未移动）
     * @param redToMove 当前行棋方
     * @param heard 语音识别出的原始文本
     * @return 按相似度从高到低排序的候选；相似度并列时保持引擎生成顺序。
     */
    public static List<Move> match(char[][] board, boolean redToMove, String heard) {
        List<Move> candidates = new ArrayList<>();
        String normalized = normalize(heard);
        if (normalized.length() < 2) return candidates;
        String heardPinyin = toPinyin(normalized);
        List<Move> legal = XiangqiRules.generateLegalMoves(board, redToMove);
        int bestScore = Integer.MAX_VALUE;
        List<Move> best = new ArrayList<>();
        for (Move move : legal) {
            String notation = ChineseNotation.translate(board, move, false);
            String notationPinyin = toPinyin(normalize(notation));
            int dist = editDistance(heardPinyin, notationPinyin);
            if (dist > allowedDistance(notationPinyin)) continue;
            if (dist < bestScore) {
                // 出现更近的候选：丢弃此前积累的所有较远候选。
                bestScore = dist;
                best.clear();
                best.add(move);
            } else if (dist == bestScore) {
                best.add(move);
            }
        }
        candidates.addAll(best);
        return candidates;
    }

    /** 与候选记谱“最接近”的一条（悬浮窗展示纠正结果用）。 */
    public static String bestNotation(char[][] board, boolean redToMove, String heard) {
        List<Move> legal = XiangqiRules.generateLegalMoves(board, redToMove);
        String normalized = normalize(heard);
        if (normalized.length() < 2) return "";
        String heardPinyin = toPinyin(normalized);
        String bestText = "";
        int bestScore = Integer.MAX_VALUE;
        for (Move move : legal) {
            String notation = ChineseNotation.translate(board, move, false);
            int dist = editDistance(heardPinyin, toPinyin(normalize(notation)));
            if (dist < bestScore) {
                bestScore = dist;
                bestText = notation;
            }
        }
        return bestText;
    }

    /** 记谱拼音长度 n 允许的编辑距离：容忍约 1/3 的错字。 */
    private static int allowedDistance(String notationPinyin) {
        return Math.max(1, notationPinyin.length() / 3);
    }

    // ==================== 归一化 ====================

    /**
     * 归一化：阿拉伯数字（含全角）→ 中文数字、常见别名 → 标准字、去空格标点。
     * 无法识别的字符原样保留（拼音化时按 unknown 处理）。
     */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '，' || c == '。' || c == '、' || c == ','
                    || c == '.' || c == '!' || c == '！' || c == '？' || c == '?') continue;
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));
            if (c >= '0' && c <= '9') {
                sb.append(CN_DIGITS[c - '0']);
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ==================== 拼音化 ====================

    /** 逐字转拼音；未知字符原样附加（保证长度信息不丢）。 */
    public static String toPinyin(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); i++) {
            String p = PINYIN.get(text.charAt(i));
            if (p != null) sb.append(p);
            else sb.append(Character.toLowerCase(text.charAt(i)));
        }
        return sb.toString();
    }

    // ==================== 编辑距离 ====================

    /** 经典 Levenshtein 编辑距离（插入/删除/替换各计 1），O(n*m)，串都很短。 */
    public static int editDistance(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[n][m];
    }
}
