package ai.forgenerative.wgswitch;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

/** Tracks when the whole app goes to the background so the biometric lock can kick back in. */
public class WgApp extends Application implements Application.ActivityLifecycleCallbacks {
    /** How long the app may sit in the background before it locks again. */
    static final long RELOCK_AFTER_MS = 60_000;

    private int started;
    private long backgroundedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(Activity a) {
        if (started++ == 0 && backgroundedAt > 0
                && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) {
            AppLock.lock();
        }
    }

    @Override
    public void onActivityStopped(Activity a) {
        // The PIN/pattern fallback screen briefly hides our activities; that isn't "leaving the app".
        if (--started == 0 && !AppLock.authenticating) backgroundedAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityResumed(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
