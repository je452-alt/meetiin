package chat.meetin.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

public class MmsReceiver extends BroadcastReceiver {
    private static final String TAG = "MmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                byte[] pushData = bundle.getByteArray("data");
                if (pushData != null) {
                    String mmsData = Base64.encodeToString(pushData, Base64.DEFAULT);
                    context.getSharedPreferences("MeetIn", Context.MODE_PRIVATE)
                        .edit().putString("mms_queue", mmsData).apply();
                    Log.d(TAG, "MMS stored");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MMS error", e);
        }
    }
}
