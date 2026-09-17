package com.tyl.xiangqi.ndxq;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
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
 *   <li>悬浮球模式：常驻小球（TYPE_APPLICATION_OVERLAY），打开后小球自动待命，
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

    // ===== 悬浮球模式 =====
    private boolean ballEnabled;
    /** 悬浮球是否被点击暂停：暂停时轮到玩家也不自动收音，再点小球恢复。 */
    private volatile boolean ballPaused;
    private WindowManager windowManager;
    private View ballView;
    private WindowManager.LayoutParams ballParams;
    private TextView ballText;
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

    /** 悬浮球开关状态（菜单按钮显示用）。 */
    boolean isFloatingBallEnabled() {
        return ballEnabled;
    }

    /** 悬浮球开关：首次开启申请悬浮窗与麦克风权限，之后切换显示/隐藏。 */
    void toggleFloatingBall() {
        if (ballEnabled) {
            stopBallSession();
            removeBallView();
            ballEnabled = false;
            Toast.makeText(host, "语音走棋悬浮球已关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(host)) {
            requestOverlayPermission();
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

    private void requestOverlayPermission() {
        try {
            Toast.makeText(host, "请授予“显示在其他应用上层”权限后重试", Toast.LENGTH_LONG).show();
            host.startActivity(new android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + host.getPackageName())));
        } catch (Exception e) {
            Toast.makeText(host, "无法打开悬浮窗权限设置：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 悬浮窗权限返回后由 Activity 调用：若用户刚开启过悬浮球则继续。 */
    void onResumeAfterSettings() {
        if (!ballEnabled && autoMode && Settings.canDrawOverlays(host)
                && host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            autoMode = false;
            enableFloatingBall();
        }
    }

    void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_RECORD_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (autoMode) {
                if (Settings.canDrawOverlays(host)) {
                    autoMode = false;
                    enableFloatingBall();
                }
                // 还差悬浮窗权限：留在 autoMode，设置页返回后 onResumeAfterSettings 继续。
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
        ensureReadyThen(() -> {
            if (ballEnabled) updateBallState(BALL_IDLE_COLOR, "待");
            Toast.makeText(host, "语音走棋悬浮球已开启：轮到你走棋时自动收音", Toast.LENGTH_LONG).show();
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

    // ==================== 悬浮球视图 ====================

    private void showBallView() {
        if (ballView != null) return;
        windowManager = (WindowManager) host.getSystemService(android.content.Context.WINDOW_SERVICE);
        ballView = new View(host);
        ballText = null;
        // 用 TextView 以便显示状态字（待/录/载）。
        TextView ball = new TextView(host);
        ball.setText("棋");
        ball.setTextSize(13);
        ball.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ball.setTextColor(Color.WHITE);
        ball.setGravity(Gravity.CENTER);
        ballView = ball;
        ballText = ball;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(BALL_LOADING_COLOR);
        ball.setBackground(bg);

        ballParams = new WindowManager.LayoutParams(
                host.dp(44), host.dp(44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = host.dp(8);
        ballParams.y = host.dp(320);

        final int[] downPos = new int[2];
        final boolean[] dragged = new boolean[]{false};
        ball.setOnTouchListener((v, event) -> {
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
                        ballParams.x += dx;
                        ballParams.y += dy;
                        downPos[0] = (int) event.getRawX();
                        downPos[1] = (int) event.getRawY();
                        try {
                            windowManager.updateViewLayout(ballView, ballParams);
                        } catch (Exception ignored) {
                        }
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
            windowManager.addView(ballView, ballParams);
        } catch (Exception e) {
            ballView = null;
            ballText = null;
            ballEnabled = false;
            Toast.makeText(host, "悬浮球创建失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateBallState(int color, String text) {
        if (ballText == null) return;
        ballText.setText(text);
        android.graphics.drawable.Drawable bg = ballText.getBackground();
        if (bg instanceof GradientDrawable) ((GradientDrawable) bg).setColor(color);
        applyBallAnimation(color);
    }

    /**
     * 按状态切换悬浮球动画：
     * 录音 = 呼吸缩放（持续脉动，提示正在收音）；
     * 等待收音（候）= 轻微缩放；
     * 待命/暂停/加载 = 静止。
     */
    private void applyBallAnimation(int color) {
        if (ballView == null) return;
        ballView.animate().cancel();
        ballView.setScaleX(1f);
        ballView.setScaleY(1f);
        ballView.setAlpha(1f);
        if (color == BALL_RECORDING_COLOR) {
            startBreathingAnimation();
        } else if (color == BALL_LOADING_COLOR && "候".equals(ballText.getText().toString())) {
            ballView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(500L)
                    .withEndAction(() -> {
                        if (ballView != null) {
                            ballView.animate().scaleX(1f).scaleY(1f).setDuration(500L).start();
                        }
                    }).start();
        }
    }

    /** 录音呼吸动画：缩放在 1.0～1.12 间往复，同时带一圈透明度变化，直到状态切换被打断。 */
    private void startBreathingAnimation() {
        if (ballView == null) return;
        ballView.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.75f).setDuration(600L)
                .withEndAction(() -> {
                    if (ballView == null) return;
                    ballView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600L)
                            .withEndAction(() -> {
                                if (ballView != null && capturing) startBreathingAnimation();
                            }).start();
                }).start();
    }

    private void removeBallView() {
        if (ballView == null || windowManager == null) return;
        ballView.animate().cancel();
        try {
            windowManager.removeView(ballView);
        } catch (Exception ignored) {
        }
        ballView = null;
        ballText = null;
    }

    /** Activity 返回/离开棋盘页时调用：停止录音但保留悬浮球与引擎。 */
    void pauseBallSession() {
        if (!ballEnabled) return;
        stopCaptureInternal();
        if (ballText != null) updateBallState(BALL_IDLE_COLOR, "待");
    }

    private void stopBallSession() {
        stopCaptureInternal();
    }

    /**
     * 点击悬浮球：在“暂停”和“待命”间切换，不销毁悬浮球。
     * 暂停 = 本回合不自动收音（录到一半也会立刻停止并丢弃）；再点一下恢复自动收音。
     * 彻底关闭请用棋盘菜单的“悬浮球：开/关”。
     */
    private void toggleBallPaused() {
        if (!ballEnabled) return;
        ballPaused = !ballPaused;
        if (ballPaused) {
            stopCaptureInternal();
            updateBallState(BALL_PAUSED_COLOR, "停");
            Toast.makeText(host, "语音走棋已暂停，点小球恢复", Toast.LENGTH_SHORT).show();
        } else {
            updateBallState(BALL_IDLE_COLOR, "待");
            Toast.makeText(host, "语音走棋已恢复：轮到你走棋时自动收音", Toast.LENGTH_SHORT).show();
            notifyHumanTurn();
        }
    }

    /**
     * 由 Activity 在轮到玩家行棋时调用：悬浮球开启且空闲时自动开始 30 秒收音。
     * 为避免电脑落子音效/语音播报串进麦克风，延迟 {@link #AUTO_CAPTURE_DELAY_MS} 再开录；
     * 若延迟期间又轮到电脑（极端快速场景）或用户已暂停，则不再启动。
     */
    void notifyHumanTurn() {
        if (!ballEnabled || ballPaused || !recognizerReady || capturing
                || host.boardView == null || host.boardView.isEditMode() || host.isRescoring) {
            return;
        }
        updateBallState(BALL_LOADING_COLOR, "候");
        host.handler.removeCallbacks(startCaptureRunnable);
        host.handler.postDelayed(startCaptureRunnable, AUTO_CAPTURE_DELAY_MS);
    }

    private final Runnable startCaptureRunnable = new Runnable() {
        @Override public void run() {
            if (!ballEnabled || ballPaused || capturing || !recognizerReady) {
                if (ballEnabled && !ballPaused) updateBallState(BALL_IDLE_COLOR, "待");
                return;
            }
            updateBallState(BALL_RECORDING_COLOR, "录");
            startCapture();
            scheduleAutoTimeout();
        }
    };

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
            recogThread = new Thread(() -> readLoop(stream, false), "sherpa-asr");
            recogThread.start();
        } catch (Exception e) {
            capturing = false;
            liveStream = null;
            Toast.makeText(host, "无法启动录音：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readLoop(OnlineStream stream, boolean silent) {
        short[] buffer = new short[SAMPLE_RATE / 10]; // 100ms 一块
        float[] samples = new float[buffer.length];
        while (capturing && audioRecord != null && recognizer != null) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read <= 0) continue;
            for (int i = 0; i < read; i++) samples[i] = buffer[i] / 32768f;
            stream.acceptWaveform(samples, SAMPLE_RATE);
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            final String text = recognizer.getResult(stream).getText();
            if (text != null && text.length() > 0) {
                host.handler.post(() -> {
                    if (liveText != null) liveText.setText(text);
                });
            }
            if (recognizer.isEndpoint(stream)) {
                // 一句话说完（静音判定）：立即收尾并应用结果。
                if (silent) {
                    host.handler.post(this::finishAutoCapture);
                } else {
                    host.handler.post(this::finishDialogCapture);
                }
                return;
            }
        }
    }

    /** 悬浮球模式：端点触发后收尾并应用识别结果。 */
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
        stopCaptureInternal();
        updateBallState(BALL_IDLE_COLOR, "待");
        applyHeard(heard == null ? "" : heard, true);
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

    private void applyHeard(String heard, boolean fromBall) {
        if (heard.trim().length() == 0) {
            if (!fromBall) Toast.makeText(host, "没有听清，请再试一次", Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.boardView == null) return;
        List<Move> candidates = VoiceMoveMatcher.match(
                host.boardView.copyBoard(), host.boardView.isRedToMove(), heard);
        if (candidates.isEmpty()) {
            host.appendLog("语音走棋：识别“" + heard + "”，但当前没有匹配的合法走法。\n");
            if (!fromBall) {
                Toast.makeText(host, "识别“" + heard + "”，但当前没有匹配的合法走法",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(host, "没有匹配“" + heard + "”的走法，请用手指走棋或长按小球重说",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        Move best = candidates.get(0);
        if (candidates.size() == 1 || highConfidence(heard, best)) {
            playHeardMove(best, heard);
            return;
        }
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
            Toast.makeText(host, "走法失效，请重新语音输入", Toast.LENGTH_SHORT).show();
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
