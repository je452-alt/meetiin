package chat.meetin.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class VerifiedActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verified);

        String name = getIntent().getStringExtra("name");
        if (name == null || name.isEmpty() || name.equals("Unknown")) {
            name = "Verified User";
        }

        TextView nameText = findViewById(R.id.verifiedName);
        nameText.setText("Welcome, " + name);

        getSharedPreferences("MeetIn", MODE_PRIVATE)
                .edit()
                .putBoolean("identity_verified", true)
                .apply();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(VerifiedActivity.this, MainActivity.class));
            finish();
        }, 3000);
    }
}
