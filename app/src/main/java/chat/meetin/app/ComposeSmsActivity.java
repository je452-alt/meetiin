package chat.meetin.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Compose SMS activity.
 * Required so Android recognizes this app as a default SMS handler.
 */
public class ComposeSmsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
