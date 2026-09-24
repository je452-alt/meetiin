package chat.meetin.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Headless SMS send service.
 * Required so Android recognizes this app as a default SMS handler.
 */
public class HeadlessSmsSendService extends Service {
    private static final String TAG = "HeadlessSmsSend";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Headless SMS service started");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
