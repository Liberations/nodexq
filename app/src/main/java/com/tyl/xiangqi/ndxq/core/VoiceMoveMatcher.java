package com.tyl.xiangqi.ndxq.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音走棋匹配器：把语音识别出的文本（可能是“炮二平五”“炮2平5”，也常带错别字，
 * 例如“码二平五”“炮二平舞”）匹配到当前局面的合法着法上。
 *
 * <p>思路与 PC 端口头报棋一致：不解析文本语法，而是把当前局面所有合法着法逐一
 * 翻译成中文记谱，再与识别文本做逐字模糊比较（数字归一 + 同音字表），
 * 返回按相似度排序的候选列表，由调用方决定直接走唯一候选还是弹窗让玩家选择。</p>
 */
public final class VoiceMoveMatcher {
    /** 与记谱数字对应的同音/近音字表：识别引擎常把“二”写成“而/贰”、“七”写成“柒”等。 */
    private static final Map<Character, Character> DIGIT_ALIASES = new HashMap<>();
    /** 棋子名的同音/近音字表：“马/码/玛”、“车/車/居”、“将/酱”等。 */
    private static final Map<Character, Character> PIECE_ALIASES = new HashMap<>();
    /** 动作字表：“进/晋/尽”、“退/腿”、“平/评/瓶”。 */
    private static final Map<Character, Character> ACTION_ALIASES = new HashMap<>();

    static {
        putAliases(DIGIT_ALIASES, "一二三四五六七八九", "一零〇一二三四五六七八九");
        putAliases(DIGIT_ALIASES, "1", "壹");
        putAliases(DIGIT_ALIASES, "2", "贰两");
        putAliases(DIGIT_ALIASES, "3", "仨");
        putAliases(DIGIT_ALIASES, "4", "肆");
        putAliases(DIGIT_ALIASES, "5", "伍");
        putAliases(DIGIT_ALIASES, "6", "陆");
        putAliases(DIGIT_ALIASES, "7", "柒");
        putAliases(DIGIT_ALIASES, "8", "捌扒");
        putAliases(DIGIT_ALIASES, "9", "玖酒");
        putAliases(PIECE_ALIASES, "车", "車砗居");
        putAliases(PIECE_ALIASES, "马", "馬码玛吗妈");
        putAliases(PIECE_ALIASES, "相", "象箱想向");
        putAliases(PIECE_ALIASES, "仕", "士事是四使");
        putAliases(PIECE_ALIASES, "帅", "率甩");
        putAliases(PIECE_ALIASES, "将", "酱蒋匠江讲");
        putAliases(PIECE_ALIASES, "炮", "砲泡袍跑炮");
        putAliases(PIECE_ALIASES, "兵", "冰丙并");
        putAliases(PIECE_ALIASES, "卒", "足族组祖");
        putAliases(PIECE_ALIASES, "前", "钱千牵签");
        putAliases(PIECE_ALIASES, "后", "候厚猴");
        putAliases(PIECE_ALIASES, "中", "忠钟终重众");
        putAliases(ACTION_ALIASES, "进", "晋近尽进既既");
        putAliases(ACTION_ALIASES, "退", "腿推腿队对");
        putAliases(ACTION_ALIASES, "平", "评瓶凭苹乒");
    }

    private VoiceMoveMatcher() {}

    private static void putAliases(Map<Character, Character> map, String canonical, String aliases) {
        for (int i = 0; i < aliases.length(); i++) {
            map.put(aliases.charAt(i), canonical.charAt(0));
        }
    }

    /**
     * 在当前局面下把识别文本匹配成合法着法候选。
     *
     * @param board 走子前棋盘（棋子尚未移动）
     * @param redToMove 当前行棋方
     * @param heard 语音识别出的原始文本
     * @return 按匹配得分从高到低排序的候选；得分相同保持引擎生成顺序。
     */
    public static List<Move> match(char[][] board, boolean redToMove, String heard) {
        List<Move> candidates = new ArrayList<>();
        String normalized = normalize(heard);
        if (normalized.length() < 2) return candidates;
        List<Move> legal = XiangqiRules.generateLegalMoves(board, redToMove);
        int bestScore = -1;
        for (Move move : legal) {
            // 按照新中文记谱，红方用“仕/相/帅/兵”，黑方用“士/象/将/卒”。
            String notation = ChineseNotation.translate(board, move, false);
            int score = similarity(normalized, normalize(notation));
            if (score <= 0) continue;
            if (score > bestScore) {
                bestScore = score;
                candidates.add(0, move);
            } else {
                candidates.add(move);
            }
        }
        return candidates;
    }

    /** 归一化：阿拉伯数字→中文数字、同音字→标准字、去空格标点。 */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '，' || c == '。' || c == '、' || c == ','
                    || c == '.' || c == '!' || c == '！' || c == '？' || c == '?') continue;
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));
            if (c >= '0' && c <= '9') {
                String[] cn = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
                sb.append(cn[c - '0']);
                continue;
            }
            Character mapped = DIGIT_ALIASES.get(c);
            if (mapped == null) mapped = PIECE_ALIASES.get(c);
            if (mapped == null) mapped = ACTION_ALIASES.get(c);
            sb.append(mapped == null ? c : mapped.charValue());
        }
        return sb.toString();
    }

    /**
     * 简单逐位相似度：两串在等长下逐位比较（记谱固定 3～5 字，识别文本取其中
     * 最长公共子序列长度作为得分，容忍多识别或漏识别一两个字）。
     * 返回 0 表示完全不相关（棋子名和动作字都对不上）。
     */
    private static int similarity(String heard, String notation) {
        if (heard.length() == 0 || notation.length() == 0) return 0;
        // LCS（最长公共子序列）：记谱串很短，O(n*m) 足够。
        int n = heard.length(), m = notation.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                dp[i][j] = heard.charAt(i - 1) == notation.charAt(j - 1)
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        int lcs = dp[n][m];
        // 棋子名必须命中才算相关：记谱第 0 位（或 1 位，前缀型“前/后/中”）对不上直接淘汰。
        String stripped = stripPrefix(notation);
        if (!heard.contains(String.valueOf(stripped.charAt(0)))) return 0;
        return lcs;
    }

    private static String stripPrefix(String notation) {
        // “前/后/中/数字”开头的同列多子记谱，取到棋子名为止。
        int index = 0;
        while (index < notation.length()
                && "前后中一二三四五六七八九".indexOf(notation.charAt(index)) >= 0) {
            index++;
        }
        return index < notation.length() ? notation.substring(index) : notation;
    }
}
