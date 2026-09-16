package chat.meetin.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class MeetInFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "meetin_channel";
    private static final String CHANNEL_NAME = "MeetIn Chat";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        getSharedPreferences("meetin_push", MODE_PRIVATE)
                .edit()
                .putString("fcm_token", token)
                .apply();
        MainActivity.dispatchFcmToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        String title = firstNonEmpty(data.get("title"),
                remoteMessage.getNotification() != null ? remoteMessage.getNotification().getTitle() : null,
                "New message");
        String body = firstNonEmpty(data.get("body"),
                remoteMessage.getNotification() != null ? remoteMessage.getNotification().getBody() : null,
                "You have a new message");

        if (MainActivity.dispatchFcmMessage(title, body, data.get("messageId"))) {
            return;
        }

        showMessageNotification(title, body, data);
    }

    private void showMessageNotification(String title, String body, Map<String, String> data) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("New MeetIn messages and alerts");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        copyExtra(data, intent, "conversationId");
        copyExtra(data, intent, "messageId");
        copyExtra(data, intent, "chatUrl");

        int requestCode = (int) (System.currentTimeMillis() & 0x7fffffff);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, requestCode, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_meetin)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        manager.notify(requestCode, builder.build());
    }

    private static void copyExtra(Map<String, String> data, Intent intent, String key) {
        String value = data.get(key);
        if (!TextUtils.isEmpty(value)) {
            intent.putExtra(key, value);
        }
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        if (!TextUtils.isEmpty(first)) return first;
        if (!TextUtils.isEmpty(second)) return second;
        return fallback;
    }
}
