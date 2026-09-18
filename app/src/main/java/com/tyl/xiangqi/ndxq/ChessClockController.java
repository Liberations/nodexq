package com.tyl.xiangqi.ndxq;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.tyl.xiangqi.ndxq.ui.UiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 棋钟：模拟比赛双方计时，首页入口进入。
 *
 * <p>规则体系（预设分组 + 自定义）：
 * <ul>
 *   <li>包干制：只有初始时间，超时判负；</li>
 *   <li>加秒制（Fischer）：结算顺序 = 先扣本步耗时 → 判超时 → 再加秒 → 换边；
 *       超时后不加秒救回；</li>
 *   <li>读秒制：基本时间用完进入读秒，每步固定读秒时长、按钟重置读秒并扣减
 *       剩余次数，次数与读秒同时耗尽判负；</li>
 *   <li>附加赛：红黑初始时间不同（默认红 6 分黑 4 分，每步 +3s），
 *       和棋黑胜，通过“结束对局”弹窗选择结果。</li>
 * </ul></p>
 *
 * <p>防误触：按钟切换后 300ms 内再按无效；当前行棋方卡片加粗描边高亮；
 * 任一方剩 10 秒内时间变红并“哔”提示。</p>
 *
 * <p>外观可自定义：背景色、字体颜色、字体大小（60%～160%），全部持久化。</p>
 */
final class ChessClockController {
    static final String PREF_BG_COLOR = "chess_clock_bg_color";
    static final String PREF_TEXT_COLOR = "chess_clock_text_color";
    static final String PREF_TEXT_SIZE = "chess_clock_text_size";
    static final String PREF_PRESET = "chess_clock_preset";

    private static final long TICK_MS = 100L;
    private static final long PRESS_LOCK_MS = 300L;
    private static final long URGENT_MS = 10_000L;

    private final MainActivity host;

    // ===== 计时状态 =====
    private boolean running;
    private boolean redToMove = true;
    private long redMs;
    private long blackMs;
    private long lastTickElapsed;
    private long currentStepElapsedMs;
    private int moveCount;
    private long pressLockUntil;
    private boolean redInByoyomi;
    private boolean blackInByoyomi;
    private int redByoyomiLeft;
    private int blackByoyomiLeft;
    private boolean redLowWarned;
    private boolean blackLowWarned;
    private String resultText = "";

    // ===== 视图 =====
    private LinearLayout clockRoot;
    private TextView redTimeView;
    private TextView blackTimeView;
    private TextView redStateView;
    private TextView blackStateView;
    private TextView statusView;
    private View redCard;
    private View blackCard;
    private Button startPauseBtn;
    private final Runnable tickRunnable = this::tick;
    private ToneGenerator tone;

    ChessClockController(MainActivity host) {
        this.host = host;
    }

    // ==================== 规则预设 ====================

    /** 一套完整时间控制：红黑各自可不同（附加赛）。 */
    private static final class Control {
        final String name;
        final String group;
        final long redBaseMs;
        final long blackBaseMs;
        final long incMs;
        final long byoyomiMs;
        final int byoyomiCount;
        final boolean tieBreakBlackWins;

        Control(String name, String group, long redBaseMs, long blackBaseMs,
                long incMs, long byoyomiMs, int byoyomiCount, boolean tieBreakBlackWins) {
            this.name = name;
            this.group = group;
            this.redBaseMs = redBaseMs;
            this.blackBaseMs = blackBaseMs;
            this.incMs = incMs;
            this.byoyomiMs = byoyomiMs;
            this.byoyomiCount = byoyomiCount;
            this.tieBreakBlackWins = tieBreakBlackWins;
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            if (redBaseMs == blackBaseMs) {
                sb.append(formatMs(redBaseMs));
            } else {
                sb.append("红").append(formatMs(redBaseMs))
                        .append(" 黑").append(formatMs(blackBaseMs));
            }
            if (incMs > 0) sb.append(" +").append(incMs / 1000).append("s");
            if (byoyomiMs > 0) {
                sb.append(" 读秒").append(byoyomiMs / 1000).append("s×").append(byoyomiCount);
            }
            if (incMs == 0 && byoyomiMs == 0) sb.append(" 包干");
            if (tieBreakBlackWins) sb.append(" · 和棋黑胜");
            return sb.toString();
        }
    }

    private static Control[] presets() {
        List<Control> list = new ArrayList<>();
        list.add(new Control("慢棋加秒", "慢棋", 3_600_000L, 3_600_000L, 30_000L, 0, 0, false));
        list.add(new Control("标准加秒", "慢棋", 1_200_000L, 1_200_000L, 5_000L, 0, 0, false));
        list.add(new Control("快棋", "快棋", 600_000L, 600_000L, 5_000L, 0, 0, false));
        list.add(new Control("超快棋", "超快棋", 300_000L, 300_000L, 3_000L, 0, 0, false));
        list.add(new Control("30分钟包干", "包干", 1_800_000L, 1_800_000L, 0, 0, 0, false));
        list.add(new Control("读秒制", "读秒", 3_600_000L, 3_600_000L, 0, 30_000L, 5, false));
        list.add(new Control("附加赛", "决胜", 360_000L, 240_000L, 3_000L, 0, 0, true));
        return list.toArray(new Control[0]);
    }

    private static final String CUSTOM_PRESET = "自定义";

    private Control loadControl(SharedPreferences sp) {
        String name = sp.getString(PREF_PRESET, "标准加秒");
        if (CUSTOM_PRESET.equals(name)) {
            long redBase = sp.getInt("chess_clock_custom_red_min", 20) * 60L * 1000L;
            long blackBase = sp.getInt("chess_clock_custom_black_min", 20) * 60L * 1000L;
            boolean tieBreak = sp.getBoolean("chess_clock_custom_tiebreak", false);
            return new Control(CUSTOM_PRESET, "自定义", redBase, blackBase,
                    sp.getInt("chess_clock_custom_inc", 5) * 1000L,
                    sp.getInt("chess_clock_custom_byo", 0) * 1000L,
                    sp.getInt("chess_clock_custom_byo_n", 0),
                    tieBreak && redBase != blackBase);
        }
        for (Control c : presets()) {
            if (c.name.equals(name)) return c;
        }
        return presets()[1];
    }

    private void initClock(Control control) {
        redMs = control.redBaseMs;
        blackMs = control.blackBaseMs;
        running = false;
        redToMove = true;
        moveCount = 0;
        currentStepElapsedMs = 0;
        redInByoyomi = false;
        blackInByoyomi = false;
        redByoyomiLeft = control.byoyomiCount;
        blackByoyomiLeft = control.byoyomiCount;
        redLowWarned = false;
        blackLowWarned = false;
        resultText = "";
        stopTicking();
    }

    // ==================== 页面 ====================

    void showClockScreen() {
        SharedPreferences sp = prefs();
        Control control = loadControl(sp);
        initClock(control);

        int bgColor = sp.getInt(PREF_BG_COLOR, UiTheme.backgroundRgb(host));
        int textColor = sp.getInt(PREF_TEXT_COLOR, Color.WHITE);
        int sizePercent = sp.getInt(PREF_TEXT_SIZE, 100);

        host.appRoot.removeAllViews();
        clockRoot = new LinearLayout(host);
        clockRoot.setOrientation(LinearLayout.VERTICAL);
        clockRoot.setBackgroundColor(bgColor);
        clockRoot.setClickable(true);

        redTimeView = bigTimeView(textColor, sizePercent);
        blackTimeView = bigTimeView(textColor, sizePercent);
        redStateView = stateView(accentRed());
        blackStateView = stateView(accentBlack());

        redCard = timeCard(redTimeView, redStateView, "红方", accentRed());
        redCard.setOnClickListener(v -> onPressClock(true));
        blackCard = timeCard(blackTimeView, blackStateView, "黑方", accentBlack());
        blackCard.setOnClickListener(v -> onPressClock(false));

        LinearLayout timeHost = new LinearLayout(host);
        timeHost.setOrientation(LinearLayout.VERTICAL);
        timeHost.addView(blackCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        timeHost.addView(redCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(host);
        statusView.setTextSize(13);
        statusView.setTextColor(textColor);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, host.dp(4), 0, host.dp(4));

        LinearLayout buttons = new LinearLayout(host);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(8));
        startPauseBtn = clockButton("开始");
        startPauseBtn.setOnClickListener(v -> toggleStartPause());
        Button finish = clockButton("结束对局");
        finish.setOnClickListener(v -> showFinishDialog());
        Button preset = clockButton("规则");
        preset.setOnClickListener(v -> showPresetDialog());
        Button appearance = clockButton("外观");
        appearance.setOnClickListener(v -> showAppearanceDialog());
        Button back = clockButton("返回");
        back.setOnClickListener(v -> {
            stopTicking();
            host.showLauncherScreen();
        });
        addButtons(buttons, startPauseBtn, finish, preset, appearance, back);

        clockRoot.addView(timeHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        clockRoot.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        clockRoot.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        host.appRoot.addView(clockRoot, rootLp);
        host.applySystemBarInsets(clockRoot);
        refresh();
        // 首次进入直接弹规则选择。
        showPresetDialog();
    }

    private int accentRed() {
        return Color.rgb(190, 55, 48);
    }

    private int accentBlack() {
        return Color.rgb(45, 65, 110);
    }

    private TextView bigTimeView(int textColor, int sizePercent) {
        TextView view = new TextView(host);
        view.setTextSize(64f * sizePercent / 100f);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextColor(textColor);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView stateView(int accent) {
        TextView view = new TextView(host);
        view.setTextSize(12);
        view.setTextColor(accent);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private View timeCard(TextView timeView, TextView stateView, String label, int accent) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        // 注意：内层卡片必须保持不可点击——点击监听挂在外层 wrapper 上，
        // 若内层 clickable 会把触摸事件整个消费掉，外层永远收不到按钟点击。
        card.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.setMargins(host.dp(10), host.dp(5), host.dp(10), host.dp(5));

        TextView labelView = new TextView(host);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setTextColor(accent);
        labelView.setGravity(Gravity.CENTER);
        card.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(timeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(stateView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout wrapper = new LinearLayout(host);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(card, cardLp);
        return wrapper;
    }

    private void styleCard(View card, boolean active) {
        View inner = ((ViewGroup) card).getChildAt(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(10));
        int accent = card == redCard ? accentRed() : accentBlack();
        bg.setColor(Color.argb(active ? 60 : 28,
                Color.red(accent), Color.green(accent), Color.blue(accent)));
        if (active && running) {
            bg.setStroke(host.dp(3), accent);
        } else {
            bg.setStroke(host.dp(1), Color.argb(80, 120, 120, 120));
        }
        inner.setBackground(bg);
    }

    private Button clockButton(String text) {
        Button button = new Button(host);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(10));
        bg.setColor(Color.argb(70, 255, 255, 255));
        button.setBackground(bg);
        button.setTextColor(Color.WHITE);
        return button;
    }

    private void addButtons(LinearLayout row, Button... buttons) {
        for (Button button : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, host.dp(46), 1f);
            lp.rightMargin = host.dp(4);
            lp.leftMargin = host.dp(2);
            row.addView(button, lp);
        }
    }

    // ==================== 计时逻辑 ====================

    private void toggleStartPause() {
        if (resultText.length() > 0) return;
        if (running) {
            stopTicking();
            startPauseBtn.setText("继续");
            statusView.setText("已暂停");
        } else {
            running = true;
            lastTickElapsed = SystemClock.elapsedRealtime();
            startPauseBtn.setText("暂停");
            refresh();
            host.handler.postDelayed(tickRunnable, TICK_MS);
        }
    }

    private void stopTicking() {
        running = false;
        host.handler.removeCallbacks(tickRunnable);
    }

    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception ignored) {
        }
    }

    /**
     * 按钟：模拟线下实体棋钟——按下去的是自己面前那一侧，
     * 自己的钟停、对方的钟开走。开局默认红先：按“红方”区即视为
     * 红方完成开局动作，黑方钟开始计时。
     */
    private void onPressClock(boolean pressedRed) {
        if (SystemClock.elapsedRealtime() < pressLockUntil) return;
        if (resultText.length() > 0) return;
        if (!running) {
            if (redToMove == pressedRed) {
                // 按下行棋方一侧 = 该方按钟，对方钟开走。
                redToMove = !pressedRed;
            }
            running = true;
            lastTickElapsed = SystemClock.elapsedRealtime();
            startPauseBtn.setText("暂停");
            pressLockUntil = lastTickElapsed + PRESS_LOCK_MS;
            refresh();
            host.handler.postDelayed(tickRunnable, TICK_MS);
            return;
        }
        if (redToMove != pressedRed) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastTickElapsed;
        lastTickElapsed = now;

        // ① 扣本步耗时（读秒中不扣基本时间）。
        if (redToMove && !redInByoyomi) redMs -= elapsed;
        if (!redToMove && !blackInByoyomi) blackMs -= elapsed;

        boolean inByoyomi = redToMove ? redInByoyomi : blackInByoyomi;
        boolean timeout = false;
        if (!inByoyomi) {
            timeout = (redToMove ? redMs : blackMs) <= 0;
        }

        moveCount++;
        // ② 加秒 / 读秒重置（超时不救回）。
        if (!timeout) {
            if (inByoyomi) {
                if (redToMove) {
                    redByoyomiLeft--;
                    if (redByoyomiLeft <= 0) timeout = true;
                } else {
                    blackByoyomiLeft--;
                    if (blackByoyomiLeft <= 0) timeout = true;
                }
            } else if (loadControl(prefs()).incMs > 0) {
                long inc = loadControl(prefs()).incMs;
                if (redToMove) redMs += inc;
                else blackMs += inc;
            }
        }

        // ③ 判超时。
        if (timeout) {
            if (redToMove) redMs = 0;
            else blackMs = 0;
            running = false;
            resultText = redToMove ? "红方超时，黑方胜" : "黑方超时，红方胜";
            beep();
            refresh();
            return;
        }
        currentStepElapsedMs = 0;
        redToMove = !redToMove;
        pressLockUntil = SystemClock.elapsedRealtime() + PRESS_LOCK_MS;
        beep();
        refresh();
    }

    private void tick() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastTickElapsed;
        lastTickElapsed = now;
        Control control = loadControl(prefs());

        if (redToMove ? redInByoyomi : blackInByoyomi) {
            currentStepElapsedMs += elapsed;
            if (control.byoyomiMs > 0 && currentStepElapsedMs >= control.byoyomiMs) {
                running = false;
                resultText = redToMove ? "红方读秒超时，黑方胜" : "黑方读秒超时，红方胜";
                beep();
                refresh();
                return;
            }
        } else {
            if (redToMove) redMs -= elapsed;
            else blackMs -= elapsed;
            if (redToMove && redMs <= 0) {
                if (control.byoyomiMs > 0 && control.byoyomiCount > 0) {
                    redMs = 0;
                    redInByoyomi = true;
                    currentStepElapsedMs = 0;
                    beep();
                } else {
                    redMs = 0;
                    running = false;
                    resultText = "红方超时，黑方胜";
                    beep();
                    refresh();
                    return;
                }
            } else if (!redToMove && blackMs <= 0) {
                if (control.byoyomiMs > 0 && control.byoyomiCount > 0) {
                    blackMs = 0;
                    blackInByoyomi = true;
                    currentStepElapsedMs = 0;
                    beep();
                } else {
                    blackMs = 0;
                    running = false;
                    resultText = "黑方超时，红方胜";
                    beep();
                    refresh();
                    return;
                }
            }
        }

        refresh();
        host.handler.postDelayed(tickRunnable, TICK_MS);
    }

    // ==================== 显示刷新 ====================

    private void refresh() {
        if (redTimeView == null) return;
        Control control = loadControl(prefs());
        SharedPreferences sp = prefs();
        int textColor = sp.getInt(PREF_TEXT_COLOR, Color.WHITE);

        long redShown = redInByoyomi
                ? Math.max(0, control.byoyomiMs - currentStepElapsedMs) : Math.max(0, redMs);
        long blackShown = blackInByoyomi
                ? Math.max(0, control.byoyomiMs - currentStepElapsedMs) : Math.max(0, blackMs);
        redTimeView.setText(formatMs(redShown));
        blackTimeView.setText(formatMs(blackShown));
        boolean urgentRed = redInByoyomi || redMs <= URGENT_MS;
        boolean urgentBlack = blackInByoyomi || blackMs <= URGENT_MS;
        redTimeView.setTextColor(urgentRed ? Color.rgb(255, 82, 68) : textColor);
        blackTimeView.setTextColor(urgentBlack ? Color.rgb(255, 82, 68) : textColor);

        redStateView.setText(sideStateText(control, true));
        blackStateView.setText(sideStateText(control, false));

        styleCard(redCard, redToMove);
        styleCard(blackCard, !redToMove);
        redCard.setAlpha(running || !redToMove ? 1f : 0.55f);
        blackCard.setAlpha(running || redToMove ? 1f : 0.55f);

        if (resultText.length() > 0) {
            statusView.setText(resultText + " · 规则“" + control.name + "”");
        } else if (running) {
            statusView.setText((redToMove ? "红方行棋" : "黑方行棋")
                    + " · 第 " + (moveCount / 2 + 1) + " 回合 · 点击行棋方计时区按钟");
        } else if ("暂停".equals(startPauseBtn.getText().toString())) {
            statusView.setText("已暂停");
        }
    }

    private String sideStateText(Control control, boolean red) {
        if (resultText.length() > 0) return "";
        if (red ? redInByoyomi : blackInByoyomi) {
            return "读秒 剩" + (red ? redByoyomiLeft : blackByoyomiLeft) + "次";
        }
        if (control.incMs > 0) return "每步+" + control.incMs / 1000 + "s";
        return control.byoyomiMs > 0 ? "基本时间" : "包干";
    }

    static String formatMs(long ms) {
        long totalSeconds = (ms + 999) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        if (ms > 0 && ms <= URGENT_MS) {
            return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, (ms / 100) % 10);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    // ==================== 弹窗 ====================

    /** 规则选择：预设按分组展示 + 自定义，选中立即应用。 */
    private void showPresetDialog() {
        boolean wasRunning = running;
        if (running) toggleStartPause();
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(14);
        panel.setPadding(pad, host.dp(6), pad, host.dp(4));
        ScrollView scroll = new ScrollView(host);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String lastGroup = null;
        for (Control control : presets()) {
            if (!control.group.equals(lastGroup)) {
                lastGroup = control.group;
                panel.addView(groupLabel(control.group));
            }
            panel.addView(presetRow(control));
        }
        panel.addView(groupLabel("自定义"));
        panel.addView(customRow());

        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle("选择棋钟规则")
                .setView(scroll)
                .setNegativeButton("取消", (d, which) -> {
                    if (wasRunning) toggleStartPause();
                })
                .show();
        presetDialogRef = dialog;
    }

    private AlertDialog presetDialogRef;

    private TextView groupLabel(String text) {
        TextView group = new TextView(host);
        group.setText(text);
        group.setTextSize(12);
        group.setTextColor(Color.rgb(110, 118, 112));
        group.setPadding(0, host.dp(8), 0, host.dp(2));
        return group;
    }

    private View presetRow(Control control) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(8));
        bg.setColor(Color.argb(24, 90, 120, 105));
        row.setBackground(bg);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = host.dp(2);

        TextView name = new TextView(host);
        name.setText(control.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(Color.rgb(42, 65, 54));
        row.addView(name);
        TextView detail = new TextView(host);
        detail.setText(control.summary());
        detail.setTextSize(12);
        detail.setTextColor(Color.rgb(100, 108, 102));
        row.addView(detail);
        row.setOnClickListener(v -> {
            prefs().edit().putString(PREF_PRESET, control.name).apply();
            initClock(loadControl(prefs()));
            refresh();
            statusView.setText("已选择“" + control.name + "”，红方先行，按红方钟开始");
            if (presetDialogRef != null) {
                presetDialogRef.dismiss();
                presetDialogRef = null;
            }
        });
        return row;
    }

    /** 自定义入口行：只显示当前自定义参数摘要，点击后弹出独立设置弹窗。 */
    private View customRow() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(8));
        bg.setColor(Color.argb(24, 90, 120, 105));
        row.setBackground(bg);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = host.dp(2);

        TextView name = new TextView(host);
        name.setText(CUSTOM_PRESET);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(Color.rgb(42, 65, 54));
        row.addView(name);
        TextView detail = new TextView(host);
        detail.setText(loadControl(prefs()).summary());
        detail.setTextSize(12);
        detail.setTextColor(Color.rgb(100, 108, 102));
        row.addView(detail);
        row.setOnClickListener(v -> showCustomDialog());
        return row;
    }

    /** 自定义设置弹窗：填写红黑时间、加秒与读秒，点击“确定”保存并应用。 */
    private void showCustomDialog() {
        SharedPreferences sp = prefs();
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(14);
        panel.setPadding(pad, host.dp(6), pad, host.dp(4));
        ScrollView scroll = new ScrollView(host);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(fieldLabel("红方时间（分钟）"));
        EditText redMin = numberInput(sp.getInt("chess_clock_custom_red_min", 20));
        panel.addView(redMin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        panel.addView(fieldLabel("黑方时间（分钟，与红方不同即附加赛）"));
        EditText blackMin = numberInput(sp.getInt("chess_clock_custom_black_min", 20));
        panel.addView(blackMin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        panel.addView(fieldLabel("每步加秒（0 为不加）"));
        EditText inc = numberInput(sp.getInt("chess_clock_custom_inc", 5));
        panel.addView(inc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        panel.addView(fieldLabel("读秒秒数（0 为无读秒）"));
        EditText byo = numberInput(sp.getInt("chess_clock_custom_byo", 0));
        panel.addView(byo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        panel.addView(fieldLabel("读秒次数"));
        EditText byoN = numberInput(sp.getInt("chess_clock_custom_byo_n", 0));
        panel.addView(byoN, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        TextView tieNote = fieldLabel("红黑时间不同即附加赛，和棋判黑胜");
        tieNote.setTextColor(Color.rgb(110, 118, 112));
        panel.addView(tieNote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(host)
                .setTitle("自定义棋钟规则")
                .setView(scroll)
                .setPositiveButton("确定", (d, which) -> {
                    int redMinutes = parse(redMin, 20, 1, 999);
                    int blackMinutes = parse(blackMin, 20, 1, 999);
                    sp.edit()
                            .putString(PREF_PRESET, CUSTOM_PRESET)
                            .putInt("chess_clock_custom_red_min", redMinutes)
                            .putInt("chess_clock_custom_black_min", blackMinutes)
                            .putInt("chess_clock_custom_inc", parse(inc, 5, 0, 600))
                            .putInt("chess_clock_custom_byo", parse(byo, 0, 0, 600))
                            .putInt("chess_clock_custom_byo_n", parse(byoN, 0, 0, 20))
                            .putBoolean("chess_clock_custom_tiebreak", redMinutes != blackMinutes)
                            .apply();
                    initClock(loadControl(prefs()));
                    refresh();
                    statusView.setText("已选择“自定义”，红方先行，按红方钟开始");
                    if (presetDialogRef != null) {
                        presetDialogRef.dismiss();
                        presetDialogRef = null;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView fieldLabel(String text) {
        TextView view = new TextView(host);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(Color.rgb(70, 78, 72));
        view.setPadding(0, host.dp(4), 0, 0);
        return view;
    }

    private EditText numberInput(int value) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(value));
        input.setSelectAllOnFocus(true);
        return input;
    }

    private int parse(EditText input, int fallback, int min, int max) {
        try {
            return host.clamp(Integer.parseInt(input.getText().toString().trim()), min, max);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 结束对局：红胜 / 黑胜 / 和棋（附加赛规则下和棋判黑胜）。 */
    private void showFinishDialog() {
        if (resultText.length() > 0) {
            statusView.setText(resultText);
            return;
        }
        boolean wasRunning = running;
        if (running) stopTicking();
        Control control = loadControl(prefs());
        String[] options = {"红方胜", "黑方胜", "和棋"};
        new AlertDialog.Builder(host)
                .setTitle("结束对局")
                .setItems(options, (d, which) -> {
                    if (which == 2 && control.tieBreakBlackWins) {
                        resultText = "和棋（附加赛规则判黑方胜）";
                    } else if (which == 0) {
                        resultText = "红方胜";
                    } else if (which == 1) {
                        resultText = "黑方胜";
                    } else {
                        resultText = "和棋";
                    }
                    running = false;
                    refresh();
                })
                .setNeutralButton("继续比赛", (d, which) -> {
                    if (wasRunning) toggleStartPause();
                })
                .setNegativeButton("取消", (d, which) -> {
                    if (wasRunning) toggleStartPause();
                })
                .show();
    }

    /** 外观设置：背景色、字体颜色、字体大小（独立于规则，保存后立即应用）。 */
    private void showAppearanceDialog() {
        SharedPreferences sp = prefs();
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(14);
        panel.setPadding(pad, host.dp(8), pad, host.dp(4));

        TextView bgLabel = appearanceLabel("背景颜色");
        panel.addView(bgLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button bgBtn = colorButton(sp.getInt(PREF_BG_COLOR, UiTheme.backgroundRgb(host)));
        bgBtn.setOnClickListener(v -> host.showColorPicker("棋钟背景色",
                currentColor(bgBtn), color -> styleColorButton(bgBtn, color)));
        panel.addView(bgBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(36)));

        TextView textLabel = appearanceLabel("字体颜色");
        panel.addView(textLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button textBtn = colorButton(sp.getInt(PREF_TEXT_COLOR, Color.WHITE));
        textBtn.setOnClickListener(v -> host.showColorPicker("棋钟字体色",
                currentColor(textBtn), color -> styleColorButton(textBtn, color)));
        panel.addView(textBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(36)));

        TextView sizeLabel = appearanceLabel("字体大小（60%～160%）");
        panel.addView(sizeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sizeSlider = new SeekBar(host);
        sizeSlider.setMax(100);
        sizeSlider.setProgress(host.clamp(sp.getInt(PREF_TEXT_SIZE, 100), 60, 160) - 60);
        panel.addView(sizeSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(38)));

        new AlertDialog.Builder(host)
                .setTitle("棋钟外观")
                .setView(panel)
                .setPositiveButton("保存", (d, which) -> {
                    sp.edit()
                            .putInt(PREF_BG_COLOR, currentColor(bgBtn))
                            .putInt(PREF_TEXT_COLOR, currentColor(textBtn))
                            .putInt(PREF_TEXT_SIZE, host.clamp(
                                    sizeSlider.getProgress() + 60, 60, 160))
                            .apply();
                    applyAppearanceKeepState();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重进页面应用新外观，保留计时状态。 */
    private void applyAppearanceKeepState() {
        long r = redMs;
        long b = blackMs;
        boolean run = running;
        boolean red = redToMove;
        int mc = moveCount;
        long stepMs = currentStepElapsedMs;
        boolean rin = redInByoyomi;
        boolean bin = blackInByoyomi;
        int rl = redByoyomiLeft;
        int bl = blackByoyomiLeft;
        String result = resultText;
        long lastTick = lastTickElapsed;
        long lock = pressLockUntil;
        boolean rw = redLowWarned;
        boolean bw = blackLowWarned;
        showClockScreen();
        redMs = r;
        blackMs = b;
        running = run;
        redToMove = red;
        moveCount = mc;
        currentStepElapsedMs = stepMs;
        redInByoyomi = rin;
        blackInByoyomi = bin;
        redByoyomiLeft = rl;
        blackByoyomiLeft = bl;
        resultText = result;
        lastTickElapsed = lastTick;
        pressLockUntil = lock;
        redLowWarned = rw;
        blackLowWarned = bw;
        startPauseBtn.setText(run ? "暂停" : (result.length() > 0 ? "开始" : "继续"));
        refresh();
        if (running) host.handler.postDelayed(tickRunnable, TICK_MS);
    }

    private TextView appearanceLabel(String text) {
        TextView view = new TextView(host);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(Color.rgb(90, 98, 93));
        view.setPadding(0, host.dp(4), 0, 0);
        return view;
    }

    private Button colorButton(int color) {
        Button button = new Button(host);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        styleColorButton(button, color);
        return button;
    }

    private void styleColorButton(Button button, int color) {
        button.setText(String.format(Locale.US, "#%06X", color & 0xFFFFFF));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(7));
        bg.setColor(color);
        bg.setStroke(host.dp(1), Color.rgb(200, 205, 200));
        button.setBackground(bg);
    }

    private int currentColor(Button button) {
        try {
            return (int) Long.parseLong(
                    button.getText().toString().replace("#", ""), 16) | 0xFF000000;
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private SharedPreferences prefs() {
        return host.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }
}
