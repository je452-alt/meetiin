package chat.meetin.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Entry point for SMS intents.
 * Required so Android recognizes this app as a default SMS handler.
 */
public class SmsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
