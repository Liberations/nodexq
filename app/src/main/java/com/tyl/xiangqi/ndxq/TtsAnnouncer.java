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
            applySavedConfiguration();
            // onInit 到来之前已产生的走子播报，在这里补放最新一条。
            String pending = pendingAnnounce;
            pendingAnnounce = null;
            if (pending != null) speakNow(pending);
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

    void announceMove(String moveText, boolean checking) {
        if (tts == null || failed) return;
        String text = checking ? moveText + "，将军" : moveText;
        if (!ready) {
            // 引擎连接中：挂起最新一条，onInit 成功后补放。
            pendingAnnounce = text;
            return;
        }
        speakNow(text);
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

    /** 只保留最新一条播报：快速连续走子时打断上一条，避免语音积压。 */
    private void speakNow(String text) {
        if (tts == null || text == null || text.length() == 0) return;
        try {
            utteranceSeq = (utteranceSeq + 1) & 0x7fffffff;
            String utteranceId = "ndxq-tts-" + System.nanoTime() + "-" + utteranceSeq;
            int status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), utteranceId);
            // 返回 ERROR 说明本次入队失败（引擎异常断开等）；释放引擎，
            // 避免此后每步走子都对着一个已失效的引擎重复调用。
            if (status == TextToSpeech.ERROR) failEngine(null);
        } catch (Exception e) {
            failEngine(null);
        }
    }
}
