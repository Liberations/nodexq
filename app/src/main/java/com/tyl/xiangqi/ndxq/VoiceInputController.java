package com.tyl.xiangqi.ndxq;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.tyl.xiangqi.ndxq.core.ChineseNotation;
import com.tyl.xiangqi.ndxq.core.Move;
import com.tyl.xiangqi.ndxq.core.VoiceMoveMatcher;

import java.util.List;

/**
 * 语音走棋：sherpa-onnx 流式 zipformer-small-ctc-zh（int8，纯离线）识别，
 * 识别文本交给 {@link VoiceMoveMatcher} 与当前局面合法着法做模糊匹配。
 *
 * <p>两种用法：
 * <ul>
 *   <li>菜单点“语音走棋”：弹对话框收音一次（与旧版一致）；</li>
 *   <li>悬浮面板模式：应用内悬浮（加在 appRoot 之上的覆盖 View，无需
 *       SYSTEM_ALERT_WINDOW 悬浮窗权限），打开后面板自动待命，
 *       每当轮到玩家行棋就自动开始录音，30 秒无有效识别自动停止并回到待命。</li>
 * </ul></p>
 */
final class VoiceInputController {
    static final int REQ_RECORD_AUDIO = 2704;
    private static final String MODEL_DIR = "asr/";
    private static final int SAMPLE_RATE = 16000;
    /** 悬浮球自动录音超时：说满 30 秒仍未识别出可用走法则放弃本回合。 */
    private static final long AUTO_CAPTURE_TIMEOUT_MS = 30_000L;
    /**
     * 我方回合开始后先延迟再收音：电脑落子的音效和 TTS 语音播报（约 2～3 秒）
     * 会被麦克风拾进去污染识别结果，等它们播完再开始录音。
     */
    private static final long AUTO_CAPTURE_DELAY_MS = 3500L;
    private static final int BALL_IDLE_COLOR = Color.argb(210, 42, 92, 70);
    private static final int BALL_LOADING_COLOR = Color.argb(210, 150, 130, 40);
    private static final int BALL_RECORDING_COLOR = Color.argb(220, 200, 60, 50);
    private static final int BALL_PAUSED_COLOR = Color.argb(200, 120, 120, 120);

    private final MainActivity host;
    private OnlineRecognizer recognizer;
    private volatile boolean recognizerReady;
    private volatile boolean recognizerLoading;
    private AudioRecord audioRecord;
    private Thread recogThread;
    private AlertDialog activeDialog;
    private TextView liveText;
    private volatile OnlineStream liveStream;

    // ===== 悬浮面板模式（应用内悬浮） =====
    private boolean ballEnabled;
    /** 面板是否被点击暂停：暂停时轮到玩家也不自动收音，再点面板恢复。 */
    private volatile boolean ballPaused;
    private View ballView;
    private volatile boolean capturing;
    private volatile boolean autoMode;
    private Runnable autoTimeoutRunnable;

    VoiceInputController(MainActivity host) {
        this.host = host;
    }

    /** 棋盘页“语音走棋”入口：走弹窗一次性收音。 */
    void startVoiceMove() {
        if (host.boardView == null || host.boardView.isEditMode() || host.isRescoring) return;
        autoMode = false;
        if (recognizerReady) {
            showCaptureDialog();
            return;
        }
        ensureReadyThen(() -> showCaptureDialog());
    }

    /** 悬浮面板开关状态（菜单按钮显示用）。 */
    boolean isFloatingBallEnabled() {
        return ballEnabled;
    }

    /** 悬浮面板开关：首次开启申请麦克风权限（应用内悬浮不需要悬浮窗权限）。 */
    void toggleFloatingBall() {
        if (ballEnabled) {
            stopBallSession();
            removeBallView();
            ballEnabled = false;
            Toast.makeText(host, "语音走棋悬浮窗已关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            autoMode = true;
            host.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
            return;
        }
        enableFloatingBall();
    }

    void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_RECORD_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (autoMode) {
                autoMode = false;
                enableFloatingBall();
                return;
            }
            ensureReadyThen(this::showCaptureDialog);
        } else {
            autoMode = false;
            Toast.makeText(host, "未获得麦克风权限，无法语音走棋", Toast.LENGTH_LONG).show();
        }
    }

    private void enableFloatingBall() {
        ballEnabled = true;
        ballPaused = false;
        showBallView();
        if (!ballEnabled) return; // 面板创建失败时已复位
        ensureReadyThen(() -> {
            if (ballEnabled) updateBallState(BALL_IDLE_COLOR, "待");
            // 常开式：面板一出现就把麦克风与背景循环拉起来，
            // 之后只做抑制/收音状态切换，不再反复开关 AudioRecord。
            startAmbientLoop();
            Toast.makeText(host, "语音走棋悬浮窗已开启：轮到你走棋时自动收音", Toast.LENGTH_LONG).show();
        });
    }

    private void ensureReadyThen(Runnable onReady) {
        if (recognizerReady) {
            onReady.run();
            return;
        }
        if (recognizerLoading) return;
        recognizerLoading = true;
        if (ballEnabled) updateBallState(BALL_LOADING_COLOR, "载");
        Toast.makeText(host, "语音引擎加载中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                OnlineModelConfig modelConfig = new OnlineModelConfig();
                modelConfig.setZipformer2Ctc(new OnlineZipformer2CtcModelConfig(
                        MODEL_DIR + "model.int8.onnx"));
                modelConfig.setTokens(MODEL_DIR + "tokens.txt");
                // bbpe.model 供识别结果做 BPE 后处理；模型卡要求一起传入。
                modelConfig.setBpeVocab(MODEL_DIR + "bbpe.model");
                modelConfig.setNumThreads(2);
                modelConfig.setProvider("cpu");
                OnlineRecognizerConfig config = new OnlineRecognizerConfig();
                config.setModelConfig(modelConfig);
                config.setFeatConfig(new FeatureConfig());
                config.setDecodingMethod("greedy_search");
                config.setEnableEndpoint(true);
                recognizer = new OnlineRecognizer(host.getAssets(), config);
                recognizerReady = true;
                host.appendLog("离线语音识别引擎加载完成。\n");
                host.handler.post(() -> {
                    recognizerLoading = false;
                    onReady.run();
                });
            } catch (Throwable e) {
                recognizerLoading = false;
                host.appendLog("语音识别引擎加载失败：" + e.getMessage() + "\n");
                host.handler.post(() -> Toast.makeText(host,
                        "语音引擎加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "sherpa-init").start();
    }

    // ==================== 悬浮面板视图 ====================
    //
    // 面板结构（圆角深色卡片，加在 appRoot 之上的应用内悬浮层）：
    //   第一行：状态字（待/候/录/停/载）+ 动态声波条（12 根柱子随状态起伏）
    //   第二行：实时识别文字（suppressCapture 时显示“对方行棋中…”）

    /** 声波柱数量与面板尺寸。 */
    private static final int WAVE_BARS = 12;
    private static final int PANEL_WIDTH_DP = 148;

    private LinearLayout panelView;
    private TextView ballText;
    private WaveBarsView waveView;
    private TextView recognizedText;
    /** 记录面板在被移除前挂靠的父容器，便于 showBallView 重挂。 */
    private ViewGroup ballHost;

    private void showBallView() {
        if (panelView != null) return;
        if (host.appRoot == null) {
            ballEnabled = false;
            return;
        }

        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = host.dp(8);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(host.dp(12));
        panelBg.setColor(Color.argb(215, 24, 34, 30));
        panel.setBackground(panelBg);

        // 第一行：状态字 + 声波条 + 关闭按钮
        LinearLayout topRow = new LinearLayout(host);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView state = new TextView(host);
        state.setText("待");
        state.setTextSize(13);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setTextColor(Color.WHITE);
        GradientDrawable stateBg = new GradientDrawable();
        stateBg.setCornerRadius(host.dp(9));
        stateBg.setColor(BALL_LOADING_COLOR);
        state.setBackground(stateBg);
        state.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(host.dp(22), host.dp(22));
        stateLp.rightMargin = host.dp(6);
        topRow.addView(state, stateLp);
        WaveBarsView wave = new WaveBarsView(host);
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(0, host.dp(24), 1f);
        topRow.addView(wave, waveLp);
        TextView closeBtn = new TextView(host);
        closeBtn.setText("✕");
        closeBtn.setTextSize(13);
        closeBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        closeBtn.setTextColor(Color.WHITE);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setCornerRadius(host.dp(9));
        closeBg.setColor(Color.argb(220, 175, 62, 55));
        closeBtn.setBackground(closeBg);
        closeBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(host.dp(22), host.dp(22));
        closeLp.leftMargin = host.dp(6);
        closeBtn.setOnClickListener(v -> closeFloatingBall());
        topRow.addView(closeBtn, closeLp);
        panel.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(26)));

        // 第二行：实时识别文字
        TextView recognized = new TextView(host);
        recognized.setText("轮到你时自动收音");
        recognized.setTextSize(11);
        recognized.setTextColor(Color.rgb(198, 214, 205));
        recognized.setSingleLine(true);
        recognized.setEllipsize(android.text.TextUtils.TruncateAt.START);
        recognized.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = host.dp(4);
        panel.addView(recognized, textLp);

        panelView = panel;
        ballText = state;
        waveView = wave;
        recognizedText = recognized;
        ballView = panel;

        // 应用内悬浮：加在 appRoot 顶层，占位小、不遮按键，可拖动。
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                host.dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.leftMargin = host.dp(8);
        panelLp.topMargin = host.dp(320);

        final int[] downPos = new int[2];
        final boolean[] dragged = new boolean[]{false};
        panel.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downPos[0] = (int) event.getRawX();
                    downPos[1] = (int) event.getRawY();
                    dragged[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) event.getRawX() - downPos[0];
                    int dy = (int) event.getRawY() - downPos[1];
                    if (Math.abs(dx) > host.dp(6) || Math.abs(dy) > host.dp(6)) {
                        dragged[0] = true;
                        panelLp.leftMargin += dx;
                        panelLp.topMargin += dy;
                        downPos[0] = (int) event.getRawX();
                        downPos[1] = (int) event.getRawY();
                        panel.setLayoutParams(panelLp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) toggleBallPaused();
                    return true;
                default:
                    return false;
            }
        });
        try {
            ballHost = host.appRoot;
            ballHost.addView(panelView, panelLp);
        } catch (Exception e) {
            panelView = null;
            ballView = null;
            ballText = null;
            waveView = null;
            recognizedText = null;
            ballEnabled = false;
            Toast.makeText(host, "悬浮面板创建失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateBallState(int color, String text) {
        if (ballText == null) return;
        ballText.setText(text);
        android.graphics.drawable.Drawable bg = ballText.getBackground();
        if (bg instanceof GradientDrawable) ((GradientDrawable) bg).setColor(color);
        if (waveView != null) {
            // 录音=大振幅随机波动；候选等待=小振幅慢波；其余=静止低柱。
            if (color == BALL_RECORDING_COLOR) {
                waveView.setMode(WaveBarsView.MODE_RECORDING);
            } else if (color == BALL_LOADING_COLOR && "候".equals(text)) {
                waveView.setMode(WaveBarsView.MODE_WAITING);
            } else {
                waveView.setMode(WaveBarsView.MODE_IDLE);
            }
        }
    }

    /** 更新悬浮面板第二行的实时识别文字；主线程调用。 */
    private void updateRecognizedText(String text) {
        if (recognizedText == null) return;
        recognizedText.setText(text == null || text.length() == 0
                ? "轮到你时自动收音" : text);
    }

    private void removeBallView() {
        if (panelView == null) return;
        panelView.animate().cancel();
        if (waveView != null) waveView.stopTicker();
        ViewGroup parent = ballHost != null ? ballHost
                : (panelView.getParent() instanceof ViewGroup ? (ViewGroup) panelView.getParent() : null);
        if (parent != null) {
            try {
                parent.removeView(panelView);
            } catch (Exception ignored) {
            }
        }
        ballHost = null;
        panelView = null;
        ballView = null;
        ballText = null;
        waveView = null;
        recognizedText = null;
    }

        /**
         * 动态声波条：N 根圆角柱，用 handler 周期刷新高度模拟声波。
         * 独立 View，避免面板整体重绘。
         */
    private static final class WaveBarsView extends View {
        static final int MODE_IDLE = 0;
        static final int MODE_WAITING = 1;
        static final int MODE_RECORDING = 2;

        private final android.os.Handler ui = new android.os.Handler(
                android.os.Looper.getMainLooper());
        private final float[] levels = new float[WAVE_BARS];
        private int mode = MODE_IDLE;
        private boolean ticking;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        WaveBarsView(android.content.Context context) {
            super(context);
            paint.setColor(Color.rgb(120, 220, 160));
        }

        void setMode(int newMode) {
            mode = newMode;
            if (mode != MODE_IDLE && !ticking) {
                ticking = true;
                ui.post(ticker);
            }
            invalidate();
        }

        void stopTicker() {
            ticking = false;
            ui.removeCallbacks(ticker);
        }

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (!ticking || mode == MODE_IDLE) return;
                float step = mode == MODE_RECORDING ? 0.34f : 0.10f;
                for (int i = 0; i < levels.length; i++) {
                    levels[i] = java.lang.Math.max(0f, java.lang.Math.min(1f,
                            levels[i] + (float) (Math.random() * 2 - 1) * step));
                }
                invalidate();
                ui.postDelayed(this, mode == MODE_RECORDING ? 70L : 160L);
            }
        };

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;
            float barWidth = width / (WAVE_BARS * 2f - 1f);
            for (int i = 0; i < WAVE_BARS; i++) {
                float level;
                if (mode == MODE_RECORDING) {
                    level = levels[i] < 0.12f ? 0.12f : levels[i];
                } else if (mode == MODE_WAITING) {
                    level = 0.18f;
                } else {
                    level = 0.10f;
                }
                float barHeight = Math.max(height * level, hostDp(2));
                float left = i * barWidth * 2;
                android.graphics.RectF rect = new android.graphics.RectF(
                        left, (height - barHeight) / 2f, left + barWidth,
                        (height + barHeight) / 2f);
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
            }
        }

        private float hostDp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    /** Activity 返回/离开棋盘页时调用：彻底停止常开录音，保留悬浮窗与引擎。 */
    void pauseBallSession() {
        if (!ballEnabled) return;
        stopCaptureInternal();
        suppressCapture = false;
        if (ballText != null) updateBallState(BALL_IDLE_COLOR, "待");
    }

    private void stopBallSession() {
        stopCaptureInternal();
    }

    /**
     * 点击悬浮面板主体：立即丢弃当前会话并重新拾音（重新开一条识别会话）。
     * 不再承担暂停/恢复职责——彻底关闭走面板右上角 ✕。
     */
    private void toggleBallPaused() {
        if (!ballEnabled || !recognizerReady) return;
        // 丢弃当前识别会话（清掉已识别的半截内容），立即开新会话重新听。
        ballPaused = false;
        suppressCapture = false;
        host.handler.removeCallbacks(startCaptureRunnable);
        startAmbientLoop();
        scheduleAutoTimeout();
        updateBallState(BALL_RECORDING_COLOR, "录");
        host.handler.post(() -> updateRecognizedText("重新拾音，请说走法"));
    }

    /** 面板右上角 ✕：停止录音、释放悬浮窗；菜单“悬浮球”开关复位。 */
    private void closeFloatingBall() {
        if (!ballEnabled) return;
        ballEnabled = false;
        ballPaused = false;
        suppressCapture = false;
        stopCaptureInternal();
        removeBallView();
        Toast.makeText(host, "语音走棋悬浮窗已关闭", Toast.LENGTH_SHORT).show();
    }

    /**
     * 由 Activity 在轮到玩家行棋时调用。
     * 常开识别下这里是“会话重置点”：清掉对方走棋期间的残留识别，延迟
     * {@link #AUTO_CAPTURE_DELAY_MS}（等音效/语音播报播完）后开始新的识别会话。
     */
    void notifyHumanTurn() {
        if (!ballEnabled || ballPaused || !recognizerReady
                || host.boardView == null || host.boardView.isEditMode() || host.isRescoring) {
            return;
        }
        exitSuppressAndListen();
    }

    /** Activity 在电脑开始思考/走子时调用：立刻抑制收音，防止播报串进识别。 */
    void notifyEngineTurnStart() {
        if (!ballEnabled) return;
        enterSuppress();
    }

    private final Runnable startCaptureRunnable = new Runnable() {
        @Override public void run() {
            if (!ballEnabled || ballPaused || !recognizerReady) {
                if (ballEnabled && !ballPaused) updateBallState(BALL_IDLE_COLOR, "待");
                return;
            }
            // 常开式：确保 AudioRecord 与背景循环都在（理论上已在），然后开新识别会话。
            suppressCapture = false;
            startAmbientLoop();
            scheduleAutoTimeout();
            updateBallState(BALL_RECORDING_COLOR, "录");
        }
    };

    /** 丢弃上一句的识别流，在仍打开的 AudioRecord 上开一个新识别流继续收音。 */
    private synchronized void restartStreamForNewUtterance() {
        if (!capturing || audioRecord == null || !recognizerReady) return;
        OnlineStream stream = recognizer.createStream("");
        liveStream = stream;
        recogThread = new Thread(() -> readLoopContinuous(audioRecord, stream), "sherpa-asr");
        recogThread.start();
    }

    private void scheduleAutoTimeout() {
        cancelAutoTimeout();
        autoTimeoutRunnable = () -> {
            if (!capturing) return;
            OnlineStream stream = liveStream;
            String heard = "";
            if (recognizer != null && stream != null) {
                try {
                    stream.inputFinished();
                    while (recognizer.isReady(stream)) recognizer.decode(stream);
                    heard = recognizer.getResult(stream).getText();
                } catch (Exception ignored) {
                }
            }
            stopCaptureInternal();
            updateBallState(BALL_IDLE_COLOR, "待");
            applyHeard(heard == null ? "" : heard, true);
        };
        host.handler.postDelayed(autoTimeoutRunnable, AUTO_CAPTURE_TIMEOUT_MS);
    }

    private void cancelAutoTimeout() {
        if (autoTimeoutRunnable != null) {
            host.handler.removeCallbacks(autoTimeoutRunnable);
            autoTimeoutRunnable = null;
        }
    }

    // ==================== 常开式连续识别（悬浮球模式） ====================

    /**
     * 悬浮球模式的“常开识别”：AudioRecord 与识别线程全程运行，不走每回合停/启。
     * 抑制（对方走棋、电脑思考）期间只丢弃音频、不喂给识别器，
     * 轮到玩家时重置识别流再开始接收，等价于一个干净的会话起点。
     * 端点判定（说完一句）在玩家回合内依旧触发识别结果。
     */
    private volatile boolean suppressCapture;

    /** 对方走棋/电脑思考开始：抑制收音（丢弃音频），悬浮球回“待”。 */
    private void enterSuppress() {
        if (!ballEnabled || suppressCapture) return;
        suppressCapture = true;
        if (capturing) {
            // 结束当前识别句（识别线程退出、流丢弃），AudioRecord 继续运行；
            // 抑制期间 readLoopContinuous 仍读麦克风但只丢帧，避免系统音频缓冲堆积。
            capturing = false; // 结束旧识别循环
            liveStream = null;
            recogThread = null;
            // 立即重开一条“只读不喂”的常开循环，保证抑制期结束后能立刻用。
            startAmbientLoop();
        }
        cancelAutoTimeout();
        updateBallState(BALL_IDLE_COLOR, "待");
        host.handler.post(() -> updateRecognizedText("对方行棋中，暂停收音…"));
    }

    /** 打开（或确保）常开 AudioRecord，并启动只读不喂的背景循环。 */
    private synchronized void startAmbientLoop() {
        if (!recognizerReady) return;
        try {
            if (audioRecord == null
                    || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf, SAMPLE_RATE));
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord 初始化失败");
                }
                audioRecord.startRecording();
            }
            if (capturing) return; // 已有循环在跑
            capturing = true;
            OnlineStream stream = recognizer.createStream("");
            liveStream = stream;
            recogThread = new Thread(() -> readLoopContinuous(audioRecord, stream), "sherpa-asr");
            recogThread.start();
        } catch (Exception e) {
            capturing = false;
            liveStream = null;
            Toast.makeText(host, "无法启动录音：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 抑制结束（轮到玩家）：等音效/播报播完后重置识别流并开始新的 30s 收音会话。 */
    private void exitSuppressAndListen() {
        if (!ballEnabled || ballPaused || !recognizerReady) return;
        suppressCapture = false;
        updateBallState(BALL_LOADING_COLOR, "候");
        host.handler.removeCallbacks(startCaptureRunnable);
        host.handler.postDelayed(startCaptureRunnable, AUTO_CAPTURE_DELAY_MS);
    }

    /**
     * 常开识别主循环：mic 一直读，但抑制期间只丢弃不喂流；
     * 端点触发后由 readLoop 自行通知收尾，等待上层重置会话。
     */
    private void readLoopContinuous(AudioRecord record, OnlineStream stream) {
        short[] buffer = new short[SAMPLE_RATE / 10]; // 100ms 一块
        float[] samples = new float[buffer.length];
        while (capturing && record == audioRecord && recognizer != null) {
            int read = record.read(buffer, 0, buffer.length);
            if (read <= 0) continue;
            if (suppressCapture) continue; // 抑制：丢弃音频，不喂识别器
            for (int i = 0; i < read; i++) samples[i] = buffer[i] / 32768f;
            stream.acceptWaveform(samples, SAMPLE_RATE);
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            final String text = recognizer.getResult(stream).getText();
            if (text != null && text.length() > 0) {
                host.handler.post(() -> {
                    if (liveText != null) liveText.setText(text);
                    updateRecognizedText(text);
                });
            }
            if (recognizer.isEndpoint(stream)) {
                host.handler.post(this::finishAutoCapture);
                return;
            }
        }
    }

    /**
     * 玩家回合内端点触发（说完一句）：立刻收尾本句、应用结果，
     * 然后**不断开麦克风**——马上重开识别会话继续听，实现真正的连续实时识别。
     * 仅当对方已在走棋（抑制中）或用户暂停时才停住。
     */
    private synchronized void finishAutoCapture() {
        if (!capturing) return;
        OnlineStream stream = liveStream;
        String heard = "";
        if (recognizer != null && stream != null) {
            try {
                stream.inputFinished();
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                heard = recognizer.getResult(stream).getText();
            } catch (Exception ignored) {
            }
        }
        cancelAutoTimeout();
        applyHeard(heard == null ? "" : heard, true);
        if (suppressCapture || ballPaused) {
            // 对方已在走棋：保持抑制待下一回合；或用户已暂停，回“停”。
            updateBallState(ballPaused ? BALL_PAUSED_COLOR : BALL_IDLE_COLOR,
                    ballPaused ? "停" : "待");
            return;
        }
        // 麦克风保持运行，立即开下一句识别（无 3.5s 延迟：走子成功后本回合
        // 会经 enterSuppress 抑制；匹配失败时玩家可以马上重说）。
        updateBallState(BALL_RECORDING_COLOR, "录");
        startAmbientLoop();
        scheduleAutoTimeout();
    }

    // ==================== 收音对话框（菜单入口） ====================

    private void showCaptureDialog() {
        if (activeDialog != null && activeDialog.isShowing()) return;
        if (!host.gameScreenVisible || host.boardView == null) return;
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(16);
        panel.setPadding(pad, pad, pad, pad);

        liveText = new TextView(host);
        liveText.setText("请说走法，例如“炮二平五”“马八进七”…");
        liveText.setTextSize(16);
        panel.addView(liveText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stop = host.compactButton("停止收音");
        stop.setOnClickListener(v -> finishDialogCapture());
        LinearLayout.LayoutParams stopLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40));
        stopLp.topMargin = host.dp(12);
        panel.addView(stop, stopLp);

        activeDialog = new AlertDialog.Builder(host)
                .setTitle("语音走棋")
                .setView(panel)
                .setNegativeButton("取消", (d, which) -> stopCapture(false))
                .setOnCancelListener(d -> stopCapture(false))
                .create();
        activeDialog.show();
        startCapture();
    }

    private void finishDialogCapture() {
        OnlineStream stream = liveStream;
        String heard = "";
        if (recognizer != null && stream != null) {
            try {
                stream.inputFinished();
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                heard = recognizer.getResult(stream).getText();
            } catch (Exception ignored) {
            }
        }
        stopCapture(false);
        applyHeard(heard == null ? "" : heard, false);
    }

    // ==================== 录音与识别 ====================

    private synchronized void startCapture() {
        if (capturing || !recognizerReady) return;
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, SAMPLE_RATE));
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord 初始化失败");
            }
            audioRecord.startRecording();
            capturing = true;
            OnlineStream stream = recognizer.createStream("");
            liveStream = stream;
            recogThread = new Thread(() -> readLoopContinuous(audioRecord, stream), "sherpa-asr");
            recogThread.start();
        } catch (Exception e) {
            capturing = false;
            liveStream = null;
            Toast.makeText(host, "无法启动录音：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private synchronized void stopCaptureInternal() {
        capturing = false;
        liveStream = null;
        host.handler.removeCallbacks(startCaptureRunnable);
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        cancelAutoTimeout();
        if (activeDialog != null) {
            try {
                activeDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeDialog = null;
        }
        liveText = null;
    }

    private void stopCapture(boolean unused) {
        stopCaptureInternal();
    }

    private void closeCaptureDialog() {
        if (activeDialog != null) {
            try {
                activeDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeDialog = null;
        }
        liveText = null;
    }

    // ==================== 结果应用 ====================

    /**
     * 应用语音识别结果：拼音模糊匹配 → 纠正为标准记谱。
     * 悬浮面板是唯一的反馈通道：成功显示纠正后的走法（如“码八进七→马八进七”），
     * 失败显示“未识别到”；不再弹错误 Toast。
     */
    private void applyHeard(String heard, boolean fromBall) {
        if (heard.trim().length() == 0) {
            host.handler.post(() -> updateRecognizedText("未识别到，请再说一次"));
            return;
        }
        if (host.boardView == null) return;
        char[][] board = host.boardView.copyBoard();
        boolean redToMove = host.boardView.isRedToMove();
        List<Move> candidates = VoiceMoveMatcher.match(board, redToMove, heard);
        if (candidates.isEmpty()) {
            host.appendLog("语音走棋：识别“" + heard + "”，但当前没有匹配的合法走法。\n");
            host.handler.post(() -> updateRecognizedText(
                    "未识别到“" + heard + "”对应的走法，请重说"));
            return;
        }
        Move best = candidates.get(0);
        String corrected = ChineseNotation.translate(board, best, false);
        if (candidates.size() == 1 || highConfidence(heard, best)) {
            host.handler.post(() -> updateRecognizedText(
                    heard.trim() + " → " + corrected));
            playHeardMove(best, heard);
            return;
        }
        // 多候选：面板展示首选纠正结果，同时弹窗让玩家确认。
        host.handler.post(() -> updateRecognizedText(heard.trim() + " → " + corrected + "？"));
        showCandidates(candidates.subList(0, Math.min(candidates.size(), 6)), heard);
    }

    /** 识别串与最佳候选的记谱重合度高（≥3 字）时视为唯一，不再弹候选。 */
    private boolean highConfidence(String heard, Move best) {
        String n1 = VoiceMoveMatcher.normalize(heard);
        String n2 = VoiceMoveMatcher.normalize(
                ChineseNotation.translate(host.boardView.copyBoard(), best, false));
        int match = 0;
        for (int i = 0; i < Math.min(n1.length(), n2.length()); i++) {
            if (n1.charAt(i) == n2.charAt(i)) match++;
        }
        return match >= 3;
    }

    private void showCandidates(List<Move> candidates, String heard) {
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            labels[i] = ChineseNotation.translate(host.boardView.copyBoard(),
                    candidates.get(i), false);
        }
        new AlertDialog.Builder(host)
                .setTitle("识别“" + heard + "”，请选择走法")
                .setItems(labels, (d, which) -> playHeardMove(candidates.get(which), heard))
                .setNegativeButton("取消", null)
                .show();
    }

    private void playHeardMove(Move move, String heard) {
        if (host.boardView == null) return;
        // 玩家语音走子与手指走子同一条链路：交给棋盘做合法性与送将校验。
        if (!host.boardView.playMove(move)) {
            host.handler.post(() -> updateRecognizedText("走法失效，请重说"));
            return;
        }
        host.appendLog("语音走棋：识别“" + heard + "”→ "
                + ChineseNotation.translate(host.boardView.copyBoard(), move, true) + "。\n");
    }

    void shutdown() {
        ballEnabled = false;
        ballPaused = false;
        stopCaptureInternal();
        removeBallView();
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        recognizerReady = false;
    }
}
