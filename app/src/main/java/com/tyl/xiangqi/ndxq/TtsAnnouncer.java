package com.tyl.xiangqi.ndxq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 走子中文语音播报：基于 Android 原生 TextToSpeech。
 *
 * <p>设置项为独立开关、语速（50%～200%）和音色（系统提供的中文语音，缺省为引擎默认
 * 音色）。播报内容沿用中文记谱（如“炮二平五”），将军时在着法后追加“将军”。
 * 语音播报与“当前声音”音效开关相互独立，只由本功能的开关控制。</p>
 *
 * <p>初始化与就绪判断参照 public-Xiangqi/tchess_flutter 的做法：
 * <ul>
 *   <li>引擎未就绪时不丢弃请求，把最近一条播报/音色查询挂起，onInit 成功后立即补放；</li>
 *   <li>onInit 里先验证简体中文语音数据，缺数据视为引擎不可用并 shutdown；</li>
 *   <li>失败路径（status 失败、语种不支持、speak 返回 ERROR）都释放引擎，
 *       避免反复调用一个坏引擎刷 “not bound to TTS engine”。</li>
 * </ul></p>
 */
final class TtsAnnouncer {
    static final String PREF_ENABLED = "tts_announce_enabled";
    static final String PREF_RATE = "tts_announce_rate";
    static final String PREF_VOICE = "tts_announce_voice";
    static final int MIN_RATE_PERCENT = 50;
    static final int MAX_RATE_PERCENT = 200;

    private final MainActivity host;
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean failed;
    private int ratePercent = 100;
    private String voiceName = "";
    private int utteranceSeq;
    /** 引擎就绪前到达的播报请求（只保留最新一条），onInit 成功后补放。 */
    private String pendingAnnounce;

    TtsAnnouncer(MainActivity host) {
        // Activity 未 attach 之前不能读 SharedPreferences 或创建 TTS，
        // 因此构造只保存引用，真正的初始化延迟到 initIfNeeded()。
        this.host = host;
    }

    /** 在 Activity attach（onCreate）之后调用一次；重复调用不会重建引擎。 */
    void initIfNeeded() {
        if (tts != null || host == null) return;
        readPrefs();
        ready = false;
        failed = false;
        tts = new TextToSpeech(host.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                failEngine("系统语音引擎初始化失败，走子语音播报不可用（请检查系统 TTS 设置）。\n");
                return;
            }
            // 引擎绑定成功不代表能说中文：必须在 onInit 里验证简体中文语音数据，
            // 缺数据时（LANG_MISSING_DATA/LANG_NOT_SUPPORTED）直接判为不可用。
            int languageStatus;
            try {
                languageStatus = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            } catch (Exception e) {
                languageStatus = TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                    || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                failEngine("系统未安装可用的中文语音数据，走子语音播报不可用"
                        + "（可在系统设置 → 语言与输入 → 文字转语音 中检查或下载中文语音）。\n");
                return;
            }
            ready = true;
            failed = false;
            // 播报完成回调驱动队列前进；必须在引擎可用后立刻注册。
            try {
                tts.setOnUtteranceProgressListener(progressListener);
            } catch (Exception ignored) {
            }
            applySavedConfiguration();
            // onInit 到来之前已产生的走子播报，在这里补放最新一条。
            String pending = pendingAnnounce;
            pendingAnnounce = null;
            if (pending != null) enqueueAnnounce(pending);
        });
    }

    private void readPrefs() {
        SharedPreferences sp = host.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        ratePercent = host.clamp(sp.getInt(PREF_RATE, 100), MIN_RATE_PERCENT, MAX_RATE_PERCENT);
        voiceName = sp.getString(PREF_VOICE, "");
    }

    boolean announceEnabled() {
        return host.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    /** 引擎初始化已确定失败（无语音引擎或缺中文语音数据等），设置页据此给出明确提示。 */
    boolean engineUnavailable() {
        return failed;
    }

    /** 引擎尚未完成 onInit 判定（连接中），设置页据此提示“稍后再试”。 */
    boolean engineConnecting() {
        return tts != null && !ready && !failed;
    }

    /** 设置页保存后调用：重新读取偏好并把语速、音色应用到引擎。 */
    void onPrefsChanged() {
        readPrefs();
        applySavedConfiguration();
    }

    /** 试听：不受开关限制，按传入的临时语速和音色朗读。 */
    void previewWith(int tempRatePercent, String tempVoiceName, String text) {
        if (text == null || text.trim().length() == 0) {
            Toast.makeText(host, "没有可播放的招法文字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tts == null || failed) {
            Toast.makeText(host, "未检测到可用的系统语音引擎，无法试听", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ready) {
            Toast.makeText(host, "语音引擎尚未就绪，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            tts.setSpeechRate(host.clamp(tempRatePercent, MIN_RATE_PERCENT, MAX_RATE_PERCENT) / 100f);
            applyVoice(tempVoiceName);
        } catch (Exception ignored) {
        }
        speakNow(text);
    }

    /** 关闭设置页后调用：丢弃试听的临时配置，恢复为已保存的偏好。 */
    void restoreSavedConfiguration() {
        applySavedConfiguration();
    }

    /**
     * 播报一步走法，文本为“红炮，二平五”式（棋子后停顿、动作连读）。
     *
     * <p>入队策略：播报进队列，不打断正在读的内容；队列只保留最近两条，
     * 保证积压时最后两条招法仍完整读出（第三条起丢弃最旧的）。
     * 将军不另行 TTS 播报——项目内置 check.wav 走系统音效通道，更即时。</p>
     */
    void announceMove(String moveText, boolean isRedMove) {
        if (tts == null || failed) return;
        String spoken = buildSpokenText(moveText, isRedMove);
        if (spoken.length() == 0) return;
        if (!ready) {
            // 引擎连接中：只保留最新一条，onInit 成功后补放。
            pendingAnnounce = spoken;
            return;
        }
        enqueueAnnounce(spoken);
    }


    /**
     * 组装播报文本：“红/黑”前缀 + 记谱原文，仅在棋子名后加一个逗号停顿，
     * 动作与数字连读更自然（“红炮，二平五”“黑马，8进7”）。
     * 记谱首字符即棋子名；带“前/后/中/数字”前缀的多子记谱同样只停顿一次。
     */
    static String buildSpokenText(String notation, boolean isRedMove) {
        if (notation == null || notation.length() == 0) return "";
        return (isRedMove ? "红" : "黑") + notation.charAt(0)
                + "，" + notation.substring(1);
    }

    /** 系统当前可用的中文语音（已按名称排序）；引擎未就绪时返回空列表。 */
    List<Voice> chineseVoices() {
        if (tts == null || !ready) return Collections.emptyList();
        List<Voice> result = new ArrayList<>();
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice voice : voices) {
                    Locale locale = voice.getLocale();
                    String language = locale == null ? "" : locale.getLanguage();
                    if (language.startsWith("zh") || language.startsWith("cmn")) result.add(voice);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(result, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    void shutdown() {
        if (tts == null) return;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
        tts = null;
        ready = false;
        failed = true;
        pendingAnnounce = null;
        synchronized (announceQueue) {
            announceQueue.clear();
        }
        speaking = false;
    }

    /** 释放引擎并把状态标记为不可用；此后所有播报请求都会被静默跳过。 */
    private void failEngine(String message) {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        ready = false;
        failed = true;
        pendingAnnounce = null;
        synchronized (announceQueue) {
            announceQueue.clear();
        }
        speaking = false;
        if (message != null) host.appendLog(message);
    }

    private void applySavedConfiguration() {
        if (tts == null || !ready) return;
        try {
            tts.setSpeechRate(ratePercent / 100f);
        } catch (Exception ignored) {
        }
        applyVoice(voiceName);
    }

    private void applyVoice(String name) {
        if (tts == null || !ready) return;
        try {
            if (name != null && name.length() > 0) {
                Voice voice = findVoice(name);
                if (voice != null) {
                    tts.setVoice(voice);
                    return;
                }
            }
            tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        } catch (Exception ignored) {
        }
    }

    private Voice findVoice(String name) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return null;
            for (Voice voice : voices) {
                if (name.equals(voice.getName())) return voice;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 播报队列上限：积压时丢弃最旧的，至少保证最后两条招法完整读出。 */
    private static final int ANNOUNCE_QUEUE_LIMIT = 2;
    private final java.util.ArrayDeque<String> announceQueue = new java.util.ArrayDeque<>();
    /** 是否有播报正在朗读（onDone/onError 之间为 true）。 */
    private volatile boolean speaking;
    private final android.speech.tts.UtteranceProgressListener progressListener =
            new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    speaking = true;
                }

                @Override public void onDone(String utteranceId) {
                    speaking = false;
                    pumpQueue();
                }

                @Deprecated
                @Override public void onError(String utteranceId) {
                    speaking = false;
                    pumpQueue();
                }
            };

    /**
     * 入队一条播报：不打断正在读的内容；队列超过上限时丢最旧的，
     * 保证最新两条招法总能完整读出。空闲时立即开始读。
     */
    private void enqueueAnnounce(String text) {
        if (text == null || text.length() == 0) return;
        synchronized (announceQueue) {
            announceQueue.addLast(text);
            while (announceQueue.size() > ANNOUNCE_QUEUE_LIMIT) {
                announceQueue.pollFirst();
            }
        }
        pumpQueue();
    }

    /** 队列泵：引擎空闲时取出下一条开始朗读；正在读则等待 onDone 回调再继续。 */
    private void pumpQueue() {
        if (tts == null || !ready || failed) return;
        if (speaking) return;
        String next;
        synchronized (announceQueue) {
            next = announceQueue.pollFirst();
        }
        if (next == null) return;
        speakNow(next);
    }

    /** 从队列取出的内容用 QUEUE_ADD 语义朗读；失败释放引擎。 */
    private void speakNow(String text) {
        if (tts == null || text == null || text.length() == 0) return;
        try {
            utteranceSeq = (utteranceSeq + 1) & 0x7fffffff;
            String utteranceId = "ndxq-tts-" + System.nanoTime() + "-" + utteranceSeq;
            int status = tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), utteranceId);
            // 返回 ERROR 说明本次入队失败（引擎异常断开等）；释放引擎，
            // 避免此后每步走子都对着一个已失效的引擎重复调用。
            if (status == TextToSpeech.ERROR) failEngine(null);
        } catch (Exception e) {
            failEngine(null);
        }
    }
}
