package com.tyl.xiangqi.ndxq;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.tyl.xiangqi.ndxq.ui.UiTheme;

import java.util.ArrayList;
import java.util.List;

/** 走子语音播报设置页面：独立开关、语速与音色选择。 */
final class TtsSettingsController {

    static void show(MainActivity activity) {
        TtsAnnouncer announcer = activity.ttsAnnouncer;
        SharedPreferences sp = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        final boolean[] enabled = new boolean[]{sp.getBoolean(TtsAnnouncer.PREF_ENABLED, false)};
        final int[] rate = new int[]{activity.clamp(sp.getInt(TtsAnnouncer.PREF_RATE, 100),
                TtsAnnouncer.MIN_RATE_PERCENT, TtsAnnouncer.MAX_RATE_PERCENT)};
        final String[] voiceName = new String[]{sp.getString(TtsAnnouncer.PREF_VOICE, "")};

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(4));

        TextView explain = new TextView(activity);
        explain.setText("开启后每完成一步走子会用系统语音朗读中文记谱（如“炮二平五”），将军时追加“将军”提示。播报与“当前声音”音效相互独立，需要系统已安装中文语音数据。");
        explain.setTextSize(11);
        explain.setTextColor(Color.rgb(105, 110, 106));
        explain.setLineSpacing(0f, 1.15f);
        explain.setPadding(0, 0, 0, activity.dp(6));
        panel.addView(explain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.CheckBox enableCheck = new android.widget.CheckBox(activity);
        enableCheck.setText("开启走子语音播报");
        enableCheck.setTextSize(14);
        enableCheck.setChecked(enabled[0]);
        enableCheck.setOnCheckedChangeListener((buttonView, isChecked) -> enabled[0] = isChecked);
        panel.addView(enableCheck, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(42)));

        TextView voiceTitle = new TextView(activity);
        voiceTitle.setText("音色");
        voiceTitle.setTextSize(13);
        voiceTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        voiceTitle.setPadding(0, activity.dp(4), 0, activity.dp(2));
        panel.addView(voiceTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(28)));

        List<Voice> voices = announcer.chineseVoices();
        List<String> voiceLabels = new ArrayList<>();
        List<String> voiceNames = new ArrayList<>();
        voiceLabels.add("系统默认");
        voiceNames.add("");
        for (Voice voice : voices) {
            voiceNames.add(voice.getName());
            voiceLabels.add(voiceLabel(voice));
        }
        int initialVoiceIndex = voiceNames.indexOf(voiceName[0]);
        if (initialVoiceIndex < 0) initialVoiceIndex = 0;
        Spinner voiceSpinner = new Spinner(activity);
        voiceSpinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, voiceLabels));
        voiceSpinner.setSelection(initialVoiceIndex);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                if (position < 0 || position >= voiceNames.size()) return;
                voiceName[0] = voiceNames.get(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        panel.addView(voiceSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(42)));

        if (voices.isEmpty()) {
            TextView noVoiceHint = new TextView(activity);
            noVoiceHint.setText(announcer.engineUnavailable()
                    ? "未检测到可用的中文语音引擎：本机可能没有安装或未启用中文 TTS（可在系统设置 → 语言与输入 → 文字转语音中检查或下载中文语音）。"
                    : announcer.engineConnecting()
                            ? "语音引擎正在初始化，请关闭本页面后重新打开以加载音色列表。"
                            : "未检测到可选音色（系统只提供默认音色），将使用系统默认中文语音。");
            noVoiceHint.setTextSize(10);
            noVoiceHint.setTextColor(Color.rgb(132, 88, 36));
            noVoiceHint.setPadding(0, 0, 0, activity.dp(4));
            panel.addView(noVoiceHint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView rateLabel = new TextView(activity);
        rateLabel.setText("语速: " + rate[0] + "%");
        rateLabel.setTextSize(12);
        rateLabel.setPadding(0, activity.dp(3), 0, 0);
        panel.addView(rateLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar rateSlider = new SeekBar(activity);
        rateSlider.setMax(TtsAnnouncer.MAX_RATE_PERCENT - TtsAnnouncer.MIN_RATE_PERCENT);
        rateSlider.setProgress(rate[0] - TtsAnnouncer.MIN_RATE_PERCENT);
        rateSlider.setContentDescription("语音播报语速");
        rateSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rate[0] = activity.clamp(progress + TtsAnnouncer.MIN_RATE_PERCENT,
                        TtsAnnouncer.MIN_RATE_PERCENT, TtsAnnouncer.MAX_RATE_PERCENT);
                rateLabel.setText("语速: " + rate[0] + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        panel.addView(rateSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(38)));

        TextView rateHint = new TextView(activity);
        rateHint.setText("语速范围 50%～200%，100% 为系统默认速度。");
        rateHint.setTextSize(10);
        rateHint.setTextColor(UiTheme.secondaryTextOnBackground(activity));
        rateHint.setPadding(0, 0, 0, activity.dp(6));
        panel.addView(rateHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.Button preview = activity.compactButton("试听播报");
        preview.setOnClickListener(v -> announcer.previewWith(rate[0], voiceName[0], "炮二平五，将军"));
        panel.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(38)));

        new AlertDialog.Builder(activity)
                .setTitle("语音播报设置")
                .setView(panel)
                .setPositiveButton("保存", (d, which) -> {
                    sp.edit()
                            .putBoolean(TtsAnnouncer.PREF_ENABLED, enabled[0])
                            .putInt(TtsAnnouncer.PREF_RATE, rate[0])
                            .putString(TtsAnnouncer.PREF_VOICE, voiceName[0])
                            .apply();
                    announcer.onPrefsChanged();
                    android.widget.Toast.makeText(activity,
                            enabled[0] ? "语音播报已开启" : "语音播报已关闭",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", (d, which) -> announcer.restoreSavedConfiguration())
                .show();
    }

    private static String voiceLabel(Voice voice) {
        java.util.Locale locale = voice.getLocale();
        String region = locale == null || locale.getCountry() == null
                || locale.getCountry().length() == 0 ? "" : " " + locale.getCountry();
        String label = "中文语音" + region + "：" + voice.getName();
        if (voice.getQuality() == Voice.QUALITY_HIGH
                || voice.getQuality() == Voice.QUALITY_VERY_HIGH) {
            label += "（高质量）";
        }
        return label;
    }
}
