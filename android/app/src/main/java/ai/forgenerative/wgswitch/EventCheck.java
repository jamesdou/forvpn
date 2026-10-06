package ai.forgenerative.wgswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Background job: every ~15 min (Android's minimum) fetch new server events and
 * turn the interesting ones into notifications. Events wait on the server, so
 * nothing is lost while the phone's tunnel is off.
 */
public class EventCheck extends JobService {
    private static final int JOB_ID = 1;
    static final String CH_ACTIVITY = "activity";
    static final String CH_SECURITY = "security";

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, EventCheck.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH_ACTIVITY, "Device activity", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel sec = new NotificationChannel(
                CH_SECURITY, "Security alerts", NotificationManager.IMPORTANCE_HIGH);
        sec.setDescription("A device connected from an address it has never used before");
        nm.createNotificationChannel(sec);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                check(this);
            } catch (Exception ignored) {
                // Tunnel off or server unreachable; events will still be there next time.
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** Fetch events newer than the last one seen and notify. Safe to call from any background thread. */
    static synchronized void check(Context c) throws Exception {
        if (Api.token(c).isEmpty()) return;
        SharedPreferences prefs = Api.prefs(c);
        long since = prefs.getLong("last_event", -1);
        JSONObject res = new JSONObject(Api.call(c, "GET", "/events?since=" + Math.max(since, 0) + "&limit=50"));
        long latest = res.optLong("latest");
        if (since >= 0) {
            String self = prefs.getString("self_name", "");
            JSONArray events = res.optJSONArray("events");
            createChannels(c);
            // Server returns newest first; notify oldest first.
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject e = events.getJSONObject(i);
                if (!worthNotifying(e, self)) continue;
                notify(c, e);
            }
        }
        // First run only records where we are, so old history does not flood the shade.
        prefs.edit().putLong("last_event", latest).apply();
    }

    private static boolean worthNotifying(JSONObject e, String self) {
        String kind = e.optString("kind");
        if ("new_ip".equals(kind)) return true;  // always, even for this phone
        if (e.optString("peer").equals(self)) return false;
        // You already know about changes you made in the app.
        if ("app".equals(e.optString("source"))) return false;
        return true;
    }

    static String title(JSONObject e) {
        String peer = e.optString("peer");
        String source = e.optString("source");
        String by = "timer".equals(source) ? " (timer)" : "schedule".equals(source) ? " (schedule)" : "";
        return switch (e.optString("kind")) {
            case "connected" -> peer + " connected";
            case "disconnected" -> peer + " disconnected";
            case "rdp_mode" -> peer + ": " + e.optString("detail");
            case "reconnected" -> peer + " reconnected";
            case "new_ip" -> peer + " connected from a new address";
            case "enabled" -> peer + " turned on" + by;
            case "disabled" -> peer + " turned off" + by;
            default -> peer + ": " + e.optString("kind");
        };
    }

    private static void notify(Context c, JSONObject e) {
        boolean security = "new_ip".equals(e.optString("kind"));
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, DeviceActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String detail = e.optString("detail");
        if (security) detail += ". If this wasn't you, turn the device off.";
        Notification n = new Notification.Builder(c, security ? CH_SECURITY : CH_ACTIVITY)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setColor(c.getColor(security ? R.color.danger_start : R.color.accent_start))
                .setContentTitle(title(e))
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setWhen((long) (e.optDouble("ts") * 1000))
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
        c.getSystemService(NotificationManager.class).notify((int) e.optLong("id"), n);
    }
}
