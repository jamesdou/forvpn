package ai.forgenerative.wgswitch;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Required biometric lock: fingerprint/face, with the phone's PIN/pattern as fallback.
 * Screens call {@link #require} from onResume and only load data once it says go.
 */
final class AppLock {
    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    private static final int OVERLAY_ID = View.generateViewId();

    private static boolean unlocked;
    static boolean authenticating;

    private AppLock() {
    }

    static void lock() {
        unlocked = false;
    }

    /** Run {@code onUnlocked} now if unlocked; otherwise cover the screen and ask for biometrics first. */
    static void require(Activity a, Runnable onUnlocked) {
        if (unlocked) {
            removeOverlay(a);
            onUnlocked.run();
            return;
        }
        TextView message = showOverlay(a);
        if (authenticating) return;

        int can = a.getSystemService(BiometricManager.class).canAuthenticate(AUTHENTICATORS);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Required means required: without any screen lock there is nothing to check against.
            message.setText("Set up a fingerprint, face unlock or screen lock on this phone to use VPN Switch.\n\nTap to open Settings.");
            message.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
            return;
        }
        message.setText("Tap to unlock");
        message.setOnClickListener(v -> require(a, onUnlocked));

        BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                .setTitle("Unlock VPN Switch")
                .setSubtitle("Confirm it's you to manage your VPN")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build();
        authenticating = true;
        prompt.authenticate(new CancellationSignal(), a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                authenticating = false;
                unlocked = true;
                removeOverlay(a);
                onUnlocked.run();
            }

            @Override
            public void onAuthenticationError(int code, CharSequence err) {
                // Cancelled or too many tries: stay locked; tapping the screen tries again.
                authenticating = false;
                message.setText(err + "\n\nTap to try again");
            }
        });
    }

    private static TextView showOverlay(Activity a) {
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View existing = decor.findViewById(OVERLAY_ID);
        if (existing != null) return existing.findViewWithTag("message");

        float d = a.getResources().getDisplayMetrics().density;
        FrameLayout overlay = new FrameLayout(a);
        overlay.setId(OVERLAY_ID);
        overlay.setBackgroundResource(R.drawable.bg_screen);
        overlay.setClickable(true);  // swallow touches meant for the screen underneath

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(32 * d);
        col.setPadding(pad, 0, pad, 0);

        FrameLayout circle = new FrameLayout(a);
        circle.setBackgroundResource(R.drawable.bg_hero);
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_shield);
        icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        int iconSize = Math.round(40 * d);
        circle.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        int circleSize = Math.round(88 * d);
        col.addView(circle, new LinearLayout.LayoutParams(circleSize, circleSize));

        TextView title = new TextView(a);
        title.setText("VPN Switch is locked");
        title.setTextColor(a.getColor(R.color.text));
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, Math.round(24 * d), 0, Math.round(8 * d));
        col.addView(title);

        TextView message = new TextView(a);
        message.setTag("message");
        message.setTextColor(a.getColor(R.color.text_muted));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        int mp = Math.round(12 * d);
        message.setPadding(mp, mp, mp, mp);
        col.addView(message);

        overlay.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        decor.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        return message;
    }

    private static void removeOverlay(Activity a) {
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View overlay = decor.findViewById(OVERLAY_ID);
        if (overlay != null) decor.removeView(overlay);
    }
}
