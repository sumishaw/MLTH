package com.captionlens.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.media.projection.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * MainActivity — Caption Lens (Whisper + LibreTranslate edition)
 *
 * Changes vs Vosk version:
 *  • Vosk entirely removed — no 1.8 GB model, no heavy background decoder
 *  • Audio captured internally (MediaProjection) in 3-second chunks
 *  • Chunks sent to whisper_server.py (localhost:8765) for transcription
 *  • Detected language auto-translated to Hindi via LibreTranslate (localhost:5000)
 *  • Overlay subtitle is full-width, left-aligned, plain text, no box/frame
 *  • Aggressive chunk sizing keeps CPU usage low on Dimensity 7050
 */
public class MainActivity extends AppCompatActivity {

    // ── Constants ────────────────────────────────────────────────────────────
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_OVERLAY          = 1002;

    private static final String WHISPER_URL      = "http://127.0.0.1:8765/transcribe";
    private static final String LIBRE_URL        = "http://127.0.0.1:5000/translate";

    // Audio capture parameters — 16 kHz mono 16-bit (Whisper native format)
    private static final int SAMPLE_RATE    = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;
    // 3-second chunks — good balance of latency vs CPU on Dimensity 7050
    private static final int CHUNK_SECONDS = 3;
    private static final int BUFFER_SIZE   = SAMPLE_RATE * 2 * CHUNK_SECONDS; // 16-bit = 2 bytes

    // ── UI ───────────────────────────────────────────────────────────────────
    private Button btnStart;
    private TextView tvStatus;
    private TextView tvTranslation;

    // ── Services & state ──────────────────────────────────────────────────────
    private MediaProjectionManager projectionManager;
    private MediaProjection         mediaProjection;
    private AudioRecord             audioRecord;

    private volatile boolean capturing = false;
    private ExecutorService captureExecutor;
    private ExecutorService networkExecutor;

    // ── Overlay ───────────────────────────────────────────────────────────────
    private WindowManager  windowManager;
    private TextView       overlayTextView;
    private WindowManager.LayoutParams overlayParams;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart     = findViewById(R.id.btn_start);
        tvStatus     = findViewById(R.id.tv_status);
        tvTranslation = findViewById(R.id.tv_translation);

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        captureExecutor = Executors.newSingleThreadExecutor();
        networkExecutor = Executors.newFixedThreadPool(2); // transcribe + translate in parallel

        btnStart.setOnClickListener(v -> {
            if (!capturing) startCapture();
            else            stopCapture();
        });

        checkWhisperReady();
    }

    // ── Whisper health check ──────────────────────────────────────────────────
    private void checkWhisperReady() {
        networkExecutor.submit(() -> {
            try {
                URL url = new URL("http://127.0.0.1:8765/ready");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                runOnUiThread(() -> {
                    if (code == 200) {
                        tvStatus.setText("✅ Whisper model ready");
                    } else {
                        tvStatus.setText("⚠ Whisper server responded: " + code);
                    }
                });
                conn.disconnect();
            } catch (Exception e) {
                runOnUiThread(() ->
                        tvStatus.setText("❌ Whisper not reachable — start whisper_server.py"));
            }
        });
    }

    // ── Start capture flow ────────────────────────────────────────────────────
    private void startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) startCapture();
            else Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            beginAudioCapture();
        }
    }

    // ── Audio capture ─────────────────────────────────────────────────────────
    private void beginAudioCapture() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Internal audio requires Android 10+", Toast.LENGTH_LONG).show();
            return;
        }

        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufSize = Math.max(minBuf * 2, BUFFER_SIZE);

        audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .build();

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            tvStatus.setText("❌ AudioRecord init failed");
            return;
        }

        capturing = true;
        btnStart.setText("STOP");
        tvStatus.setText("🎙 Capturing…");
        createOverlay();
        audioRecord.startRecording();

        captureExecutor.submit(this::captureLoop);
    }

    /**
     * captureLoop — runs on captureExecutor thread.
     * Reads 3-second PCM chunks and hands each to networkExecutor for
     * Whisper transcription + LibreTranslate → Hindi.
     */
    private void captureLoop() {
        byte[] chunk = new byte[BUFFER_SIZE];
        ByteArrayOutputStream pcmAccumulator = new ByteArrayOutputStream();
        int bytesPerChunk = BUFFER_SIZE;  // 3 s worth

        while (capturing) {
            int read = audioRecord.read(chunk, 0, chunk.length);
            if (read <= 0) continue;

            pcmAccumulator.write(chunk, 0, read);

            if (pcmAccumulator.size() >= bytesPerChunk) {
                final byte[] pcm = pcmAccumulator.toByteArray();
                pcmAccumulator.reset();

                networkExecutor.submit(() -> processChunk(pcm));
            }
        }

        // Flush remainder
        byte[] remaining = pcmAccumulator.toByteArray();
        if (remaining.length > SAMPLE_RATE) { // at least 0.5 s
            networkExecutor.submit(() -> processChunk(remaining));
        }
    }

    /**
     * processChunk — runs on networkExecutor thread.
     * 1. Send PCM → Whisper → text + detected language
     * 2. If not Hindi/English, send to LibreTranslate → Hindi
     * 3. Update overlay
     */
    private void processChunk(byte[] pcmBytes) {
        try {
            // Step 1: wrap PCM in WAV and send to Whisper
            byte[] wavBytes = pcmToWav(pcmBytes, SAMPLE_RATE, 1, 16);
            String whisperJson = httpPost(WHISPER_URL, wavBytes, "audio/wav");
            if (whisperJson == null) return;

            JSONObject whisperResult = new JSONObject(whisperJson);
            String text     = whisperResult.optString("text", "").trim();
            String srcLang  = whisperResult.optString("language", "en");

            if (text.isEmpty()) return;

            // Step 2: Translate to Hindi via LibreTranslate
            String hindiText = translateToHindi(text, srcLang);
            String displayText = (hindiText != null && !hindiText.isEmpty()) ? hindiText : text;

            // Step 3: Update overlay on main thread
            final String finalText = displayText;
            runOnUiThread(() -> {
                updateOverlay(finalText);
                tvTranslation.setText(finalText);
            });

        } catch (Exception e) {
            // Non-fatal — skip this chunk
            System.err.println("[CaptionLens] processChunk error: " + e.getMessage());
        }
    }

    private String translateToHindi(String text, String srcLang) {
        // Skip translation if already Hindi (hi) or if language detection uncertain
        if ("hi".equals(srcLang)) return text;

        try {
            JSONObject body = new JSONObject();
            body.put("q",      text);
            body.put("source", srcLang.isEmpty() ? "auto" : srcLang);
            body.put("target", "hi");
            body.put("format", "text");

            byte[] bodyBytes = body.toString().getBytes("UTF-8");
            String response  = httpPostJson(LIBRE_URL, bodyBytes);
            if (response == null) return null;

            JSONObject result = new JSONObject(response);
            return result.optString("translatedText", null);

        } catch (Exception e) {
            System.err.println("[CaptionLens] translate error: " + e.getMessage());
            return null;
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────
    private String httpPost(String urlStr, byte[] body, String contentType) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000); // Whisper may take up to 10s on slow CPU
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            conn.disconnect();
            return baos.toString("UTF-8");

        } catch (Exception e) {
            System.err.println("[CaptionLens] httpPost error: " + e.getMessage());
            return null;
        }
    }

    private String httpPostJson(String urlStr, byte[] body) {
        return httpPost(urlStr, body, "application/json; charset=UTF-8");
    }

    // ── WAV header builder ────────────────────────────────────────────────────
    private static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int dataLen   = pcm.length;
        int totalLen  = dataLen + 44 - 8;
        int byteRate  = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        ByteArrayOutputStream out = new ByteArrayOutputStream(dataLen + 44);
        DataOutputStream dos = new DataOutputStream(out);
        try {
            dos.writeBytes("RIFF");
            writeLe32(dos, totalLen);
            dos.writeBytes("WAVEfmt ");
            writeLe32(dos, 16);
            writeLe16(dos, 1);             // PCM
            writeLe16(dos, channels);
            writeLe32(dos, sampleRate);
            writeLe32(dos, byteRate);
            writeLe16(dos, blockAlign);
            writeLe16(dos, bitsPerSample);
            dos.writeBytes("data");
            writeLe32(dos, dataLen);
            dos.write(pcm);
        } catch (IOException ignored) {}
        return out.toByteArray();
    }

    private static void writeLe32(DataOutputStream d, int v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
        d.write((v >> 16) & 0xFF); d.write((v >> 24) & 0xFF);
    }
    private static void writeLe16(DataOutputStream d, int v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
    }

    // ── Stop capture ──────────────────────────────────────────────────────────
    private void stopCapture() {
        capturing = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        removeOverlay();
        btnStart.setText("START — CAPTURE VIDEO AUDIO");
        tvStatus.setText("Stopped");
    }

    // ── Overlay ───────────────────────────────────────────────────────────────
    /**
     * Creates a FULL-WIDTH, left-aligned, plain-text overlay at the bottom
     * of the screen. No box, no background frame — transparent bg, white text
     * with a subtle shadow for readability over any content.
     */
    private void createOverlay() {
        if (overlayTextView != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayTextView = new TextView(this);
        overlayTextView.setTextColor(Color.WHITE);
        overlayTextView.setTextSize(18f);
        overlayTextView.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        overlayTextView.setTypeface(null, Typeface.BOLD);
        overlayTextView.setGravity(Gravity.START | Gravity.BOTTOM);
        overlayTextView.setPadding(16, 8, 16, 8);
        overlayTextView.setBackground(null);       // NO box or frame
        overlayTextView.setMaxLines(3);
        overlayTextView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overlayTextView.setText(""); // start empty

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,   // FULL WIDTH
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        // Position at bottom-left
        overlayParams.gravity = Gravity.BOTTOM | Gravity.START;
        overlayParams.x = 0;
        overlayParams.y = 48; // small bottom margin

        try {
            windowManager.addView(overlayTextView, overlayParams);
        } catch (Exception e) {
            tvStatus.setText("❌ Overlay add failed: " + e.getMessage());
        }
    }

    private void updateOverlay(String text) {
        if (overlayTextView == null) return;
        overlayTextView.setText(text);
    }

    private void removeOverlay() {
        if (overlayTextView != null && windowManager != null) {
            try { windowManager.removeView(overlayTextView); } catch (Exception ignored) {}
            overlayTextView = null;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCapture();
        captureExecutor.shutdownNow();
        networkExecutor.shutdownNow();
    }
}
