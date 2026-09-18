package com.tyl.xiangqi.ndxq;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * 盲棋读谱：把已有棋谱主线通过 TTS 逐手顺序演示。
 *
 * <p>交互：点底部“播放棋谱”→ 弹出间隔设置（默认 3 秒）→ 弹窗关闭即开始播放，
 * 底部按钮变为“停止播放”；播放结束或点击停止后按钮恢复“播放棋谱”。
 * 播放期间只读谱不读其他来源：suppressMoveAnnounce 抑制走子链路的常规播报，
 * 避免同一手被播两次。</p>
 */
final class BlindfoldPlaybackController {
    static final String PREF_INTERVAL = "blindfold_read_interval";

    private final MainActivity host;
    private boolean playing;
    private int playPly;
    private Button hostButton;
    private final Runnable tickRunnable = this::tick;

    BlindfoldPlaybackController(MainActivity host) {
        this.host = host;
    }

    boolean isPlaying() {
        return playing;
    }

    int readIntervalSeconds() {
        return host.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getInt(PREF_INTERVAL, 3);
    }

    /** 底部入口按钮：播放中显示“停止播放”，空闲显示“播放棋谱”。 */
    void bindButton(Button button) {
        hostButton = button;
        refreshButtonText();
    }

    private void refreshButtonText() {
        if (hostButton != null) {
            // 按钮同时显示读谱状态与当前人机难度名。
            hostButton.setText((playing ? "停止播放" : "播放棋谱"));
        }
    }

    /** 点击底部按钮：未播放时先弹间隔设置，弹窗关闭即开始；播放中则直接停止。 */
    void onButtonClicked() {
        if (playing) {
            stop();
            refreshButtonText();
            return;
        }
        showIntervalDialog();
    }

    private void start() {
        if (host.engineMoves.isEmpty()) {
            host.appendLog("播放棋谱：当前没有棋谱可播。\n");
            return;
        }
        if (!host.ttsAnnouncer.announceEnabled()) {
            // 播谱依赖 TTS：自动打开播报开关。
            host.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(TtsAnnouncer.PREF_ENABLED, true).apply();
            host.ttsAnnouncer.onPrefsChanged();
        }
        // 从头顺序演示整谱。
        playPly = 0;
        playing = true;
        host.navigateToPly(0);
        refreshButtonText();
        host.appendLog("播放棋谱开始：共 " + host.engineMoves.size()
                + " 手，间隔 " + readIntervalSeconds() + " 秒。\n");
        host.handler.postDelayed(tickRunnable, readIntervalSeconds() * 1000L);
    }

    void stop() {
        if (!playing) return;
        playing = false;
        host.handler.removeCallbacks(tickRunnable);
        refreshButtonText();
        host.appendLog("播放棋谱结束。\n");
    }

    /**
     * 逐手演示：只做 navigateToPly 前进跳转，不主动调 TTS——
     * 前进导航本身会触发“播放走子音效 + 播报该手记谱”的既有链路，
     * 从根本上避免同一手被读两次。
     */
    private void tick() {
        if (!playing) return;
        if (playPly >= host.engineMoves.size()) {
            playing = false;
            refreshButtonText();
            host.appendLog("播放棋谱结束。\n");
            return;
        }
        host.navigateToPly(playPly + 1);
        playPly++;
        host.handler.postDelayed(tickRunnable, readIntervalSeconds() * 1000L);
    }

    /** 间隔设置弹窗：确认后立即开始播放。 */
    private void showIntervalDialog() {
        SharedPreferences sp = host.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(14);
        panel.setPadding(pad, host.dp(8), pad, host.dp(4));

        TextView label = new TextView(host);
        label.setText("每手播报间隔（秒，1～60）");
        label.setTextSize(13);
        panel.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(readIntervalSeconds()));
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(44)));

        new AlertDialog.Builder(host)
                .setTitle("播放棋谱")
                .setView(panel)
                .setPositiveButton("开始播放", (d, which) -> {
                    try {
                        int seconds = Integer.parseInt(input.getText().toString().trim());
                        sp.edit().putInt(PREF_INTERVAL, host.clamp(seconds, 1, 60)).apply();
                    } catch (Exception ignored) {
                    }
                    start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 人机难度选择：复用 {@link DifficultyProfiles} 的 22 档配置。 */
    void showDifficultyPicker() {
        String[] labels = new String[MainActivity.difficultyCount()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = (i + 1) + ". " + host.difficultyDisplayName(i);
        }
        new AlertDialog.Builder(host)
                .setTitle("人机难度")
                .setItems(labels, (d, which) -> {
                    host.applyBlindfoldDifficulty(which);
                    refreshButtonText();
                    host.appendLog("盲棋：人机难度已切换为 "
                            + host.difficultyDisplayName(which) + "。\n");
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
