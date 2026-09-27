package chat.meetin.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VerifyingActivity extends AppCompatActivity {
    private static final String TAG = "VerifyingActivity";
    private static final String SERVER_URL = "https://meetin-kyc-server-ht6d.onrender.com/scan";

    private TextView statusText;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verifying);

        statusText = findViewById(R.id.statusText);

        String documentBase64 = getIntent().getStringExtra("documentBase64");
        if (documentBase64 == null) {
            Toast.makeText(this, "No document provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        sendToServer(documentBase64);
    }

    private void sendToServer(String documentBase64) {
        try {
            String deviceId = DeviceInfo.getDeviceId(this);

            JSONObject json = new JSONObject();
            json.put("deviceId", deviceId);
            json.put("documentBase64", documentBase64);
            json.put("documentType", "");

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL)
                    .post(body)
                    .build();

            Log.d(TAG, "Sending to server...");

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Network error", e);
                    runOnUiThread(() -> {
                        Toast.makeText(VerifyingActivity.this,
                                "Network error. Check your connection.",
                                Toast.LENGTH_LONG).show();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            startActivity(new Intent(VerifyingActivity.this, KycActivity.class));
                            finish();
                        }, 3000);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Server response: " + responseBody);

                    try {
                        JSONObject json = new JSONObject(responseBody);
                        boolean approved = json.optBoolean("approved", false);
                        String name = json.optString("name", "Unknown");
                        String message = json.optString("message", "");

                        runOnUiThread(() -> {
                            if (approved) {
                                Intent intent = new Intent(VerifyingActivity.this, VerifiedActivity.class);
                                intent.putExtra("name", name);
                                startActivity(intent);
                                finish();
                            } else {
                                Toast.makeText(VerifyingActivity.this,
                                        "Document rejected: " + message,
                                        Toast.LENGTH_LONG).show();
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    startActivity(new Intent(VerifyingActivity.this, KycActivity.class));
                                    finish();
                                }, 3000);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Parse error", e);
                        runOnUiThread(() -> {
                            Toast.makeText(VerifyingActivity.this,
                                    "Server error. Try again.",
                                    Toast.LENGTH_LONG).show();
                            finish();
                        });
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Send error", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
