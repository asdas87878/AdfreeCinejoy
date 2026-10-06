package com.adfree.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen single-site viewer: Firefox's engine (GeckoView) with uBlock Origin built in,
 * plus video helpers (speed, gestures, lock, picture-in-picture) and colour themes.
 */
public class MainActivity extends Activity {
    private static final String TAG = "AdFreeViewer";
    private static final String UBLOCK_URI = "resource://android/assets/ublock/";
    private static final String UBLOCK_ID = "uBlock0@raymondhill.net";
    private static final String HELPER_URI = "resource://android/assets/helper/";
    private static final String HELPER_ID = "helper@adfree.viewer";

    // settings keys
    private static final String PREFS = "settings";
    private static final String K_URL = "start_url";
    private static final String K_LAST = "last_url";
    private static final String K_RESUME = "resume";
    private static final String K_PULL = "pull_refresh";
    private static final String K_GEST = "gestures";
    private static final String K_PIP = "auto_pip";
    private static final String K_MENU = "menu_button";
    private static final String K_INV = "th_invert";
    private static final String K_HUE = "th_hue";
    private static final String K_DIM = "th_dim";
    private static final String K_WARM = "th_warm";
    private static final String K_CSS = "th_css";

    private static GeckoRuntime sRuntime;
    private static GeckoResult<WebExtension> sHelper;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private GeckoSession session;
    private Root root;
    private TextView hud, pullView, menuBtn, lockBtn, speedBtn;
    private LinearLayout fsBar;

    private boolean canGoBack = false;
    private boolean inFullscreen = false;
    private boolean inPip = false;
    private boolean pipLayoutSent = false;
    private boolean locked = false;
    private String siteHost = null;
    private int scrollY = 0;
    private double speed = 1.0;

    // one port per page/frame; value = that frame has a playing video
    private final Map<WebExtension.Port, Boolean> ports = new HashMap<>();
    private boolean anyPlaying = false;
    private int vidW = 16, vidH = 9;

    // ======================================================================
    // engine
    // ======================================================================

    private static GeckoRuntime runtime(Activity a) {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    .build();
            sRuntime = GeckoRuntime.create(a.getApplicationContext(), settings);
            WebExtensionController c = sRuntime.getWebExtensionController();
            c.ensureBuiltIn(UBLOCK_URI, UBLOCK_ID).accept(
                    ext -> Log.i(TAG, "uBlock Origin ready"),
                    e -> Log.e(TAG, "uBlock Origin failed to load", e));
            sHelper = c.ensureBuiltIn(HELPER_URI, HELPER_ID);
        }
        return sRuntime;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build());
        session.setNavigationDelegate(navigation);
        session.setContentDelegate(content);
        session.setScrollDelegate(scroll);
        session.open(runtime(this));
        root.gecko.setSession(session);

        final GeckoSession mine = session;
        sHelper.accept(
                ext -> runOnUiThread(() -> {
                    if (ext != null && session == mine) {
                        mine.getWebExtensionController().setMessageDelegate(ext, messages, "browser");
                    }
                }),
                e -> Log.e(TAG, "Helper failed to load", e));

        String start = savedUrl();
        if (start.isEmpty()) {
            askForSite(true);
        } else {
            String last = prefs().getString(K_LAST, "");
            boolean resume = pref(K_RESUME, true) && !last.isEmpty()
                    && sameSite(baseHost(Uri.parse(start).getHost()), Uri.parse(last).getHost());
            openSite(start, resume ? last : start);
        }
        refreshOverlays();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pipLayoutSent && !isInPictureInPictureMode()) {
            pipLayoutSent = false;
            broadcast(msg("pip", "v", false));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Not visible any more (and not in a PiP window): pause playback.
        if (!isChangingConfigurations()) broadcast(msg("pause"));
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.close();
        session = null;
        super.onDestroy();
    }

    // ======================================================================
    // settings
    // ======================================================================

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean pref(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    private String savedUrl() {
        String url = prefs().getString(K_URL, null);
        return url != null ? url : BuildConfig.START_URL;
    }

    private void openSite(String start, String load) {
        siteHost = baseHost(Uri.parse(start).getHost());
        session.loadUri(load);
    }

    private void askForSite(boolean firstRun) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("example.com");
        input.setText(savedUrl());

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Which site should this app open?")
                .setView(padded(input))
                .setPositiveButton("Open", (d, w) -> {
                    String url = normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        askForSite(firstRun);
                        return;
                    }
                    prefs().edit().putString(K_URL, url).remove(K_LAST).apply();
                    openSite(url, url);
                });
        if (firstRun) {
            b.setCancelable(false).setNegativeButton("Quit", (d, w) -> finish());
        } else {
            b.setNegativeButton("Cancel", null);
        }
        b.show();
    }

    private static String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        return s;
    }

    private static String baseHost(String host) {
        if (host == null) return null;
        host = host.toLowerCase();
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static boolean sameSite(String site, String host) {
        if (site == null || host == null) return true;
        host = baseHost(host);
        return host.equals(site) || host.endsWith("." + site) || site.endsWith("." + host);
    }

    private void saveLastPage(String url) {
        if (url == null) return;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if ((scheme.equals("http") || scheme.equals("https")) && sameSite(siteHost, u.getHost())) {
            prefs().edit().putString(K_LAST, url).apply();
        }
    }

    // ======================================================================
    // talking to the in-page helper
    // ======================================================================

    private static JSONObject msg(String type, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void broadcast(JSONObject m) {
        for (WebExtension.Port p : new ArrayList<>(ports.keySet())) {
            try {
                p.postMessage(m);
            } catch (Exception ignored) {
            }
        }
    }

    private JSONObject themeMsg() {
        SharedPreferences p = prefs();
        return msg("theme",
                "invert", p.getBoolean(K_INV, false),
                "hue", p.getInt(K_HUE, 0),
                "dim", p.getInt(K_DIM, 100),
                "warm", p.getInt(K_WARM, 0),
                "css", p.getString(K_CSS, ""));
    }

    private final WebExtension.PortDelegate portDelegate = new WebExtension.PortDelegate() {
        @Override
        public void onPortMessage(Object message, WebExtension.Port port) {
            if (!(message instanceof JSONObject)) return;
            JSONObject m = (JSONObject) message;
            String t = m.optString("t");
            if (t.equals("hello")) {
                port.postMessage(msg("speed", "v", speed));
                port.postMessage(themeMsg());
                if (pipLayoutSent) port.postMessage(msg("pip", "v", true));
            } else if (t.equals("playing")) {
                boolean p = m.optBoolean("v");
                ports.put(port, p);
                if (p && m.optInt("w") > 0 && m.optInt("h") > 0) {
                    vidW = m.optInt("w");
                    vidH = m.optInt("h");
                }
                playingChanged();
            }
        }

        @Override
        public void onDisconnect(WebExtension.Port port) {
            ports.remove(port);
            playingChanged();
        }
    };

    private final WebExtension.MessageDelegate messages = new WebExtension.MessageDelegate() {
        @Override
        public void onConnect(WebExtension.Port port) {
            ports.put(port, false);
            port.setDelegate(portDelegate);
        }
    };

    private void playingChanged() {
        boolean p = false;
        for (Boolean b : ports.values()) p |= b;
        anyPlaying = p;
        updateKeepScreenOn();
        updatePipParams();
    }

    private void updateKeepScreenOn() {
        if (anyPlaying || inFullscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ======================================================================
    // navigation: block pop-ups and redirects to other sites, remember last page
    // ======================================================================

    private final GeckoSession.NavigationDelegate navigation = new GeckoSession.NavigationDelegate() {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        // Both signatures, so it works whichever one this engine version calls.
        public void onLocationChange(GeckoSession s, String url,
                                     List<GeckoSession.PermissionDelegate.ContentPermission> perms) {
            saveLastPage(url);
        }

        public void onLocationChange(GeckoSession s, String url,
                                     List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     Boolean hasUserGesture) {
            saveLastPage(url);
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            Uri uri = Uri.parse(request.uri);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

            if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("moz-extension")
                    || scheme.equals("resource") || scheme.equals("data")) {
                return allow();
            }
            // intent:, market:, tel: etc. — usually ads trying to open other apps.
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return deny();
            }
            if (!sameSite(siteHost, uri.getHost())) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Blocked: " + uri.getHost(), Toast.LENGTH_SHORT).show());
                return deny();
            }
            if (request.target == TARGET_WINDOW_NEW) {
                s.loadUri(request.uri);
                return deny();
            }
            return allow();
        }
    };

    private static GeckoResult<AllowOrDeny> allow() {
        return GeckoResult.fromValue(AllowOrDeny.ALLOW);
    }

    private static GeckoResult<AllowOrDeny> deny() {
        return GeckoResult.fromValue(AllowOrDeny.DENY);
    }

    private final GeckoSession.ScrollDelegate scroll = new GeckoSession.ScrollDelegate() {
        @Override
        public void onScrollChanged(GeckoSession s, int x, int y) {
            scrollY = y;
        }
    };

    // ======================================================================
    // full-screen video, crashes
    // ======================================================================

    private final GeckoSession.ContentDelegate content = new GeckoSession.ContentDelegate() {
        @Override
        public void onFullScreen(GeckoSession s, boolean fullScreen) {
            setFullscreen(fullScreen);
        }

        @Override
        public void onCrash(GeckoSession s) {
            restartAfterCrash();
        }

        @Override
        public void onKill(GeckoSession s) {
            restartAfterCrash();
        }
    };

    private void restartAfterCrash() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Page crashed, reloading…", Toast.LENGTH_SHORT).show();
            recreate();
        });
    }

    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean fs) {
        inFullscreen = fs;
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (fs) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            w.getDecorView().setSystemUiVisibility(fs
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
        if (fs) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            showFsBar();
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            locked = false;
            fsBar.setVisibility(View.GONE);
            // hand brightness back to the system
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            w.setAttributes(lp);
        }
        updateKeepScreenOn();
        refreshOverlays();
    }

    // ======================================================================
    // picture-in-picture
    // ======================================================================

    private boolean pipSupported() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        float r = vidH > 0 ? (float) vidW / vidH : 16f / 9f;
        r = Math.max(0.42f, Math.min(2.38f, r));
        b.setAspectRatio(new Rational(Math.round(r * 1000), 1000));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pref(K_PIP, true) && anyPlaying && !locked);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    private void updatePipParams() {
        if (!pipSupported()) return;
        try {
            setPictureInPictureParams(pipParams());
        } catch (Exception ignored) {
        }
    }

    private void sendPipLayout() {
        if (!inFullscreen) {
            pipLayoutSent = true;
            broadcast(msg("pip", "v", true));
        }
    }

    private void enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, "Picture-in-picture isn't available on this phone", Toast.LENGTH_SHORT).show();
            return;
        }
        sendPipLayout();
        try {
            enterPictureInPictureMode(pipParams());
        } catch (Exception e) {
            Toast.makeText(this, "Picture-in-picture is turned off for this app in Android settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pref(K_PIP, true) && anyPlaying && !locked && pipSupported()) {
            if (Build.VERSION.SDK_INT >= 31) {
                sendPipLayout(); // Android enters PiP by itself
            } else {
                enterPip();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        inPip = isInPip;
        if (!isInPip && pipLayoutSent) {
            pipLayoutSent = false;
            broadcast(msg("pip", "v", false));
        }
        if (isInPip) {
            hud.setVisibility(View.GONE);
            fsBar.setVisibility(View.GONE);
            pullView.setVisibility(View.GONE);
        }
        refreshOverlays();
    }

    // ======================================================================
    // on-screen controls
    // ======================================================================

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView pill(String text, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(dp(22));
        t.setBackground(bg);
        return t;
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
    }

    private void buildUi() {
        root = new Root(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(root.gecko, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        pullView = pill("↓  Pull to refresh", 14);
        FrameLayout.LayoutParams lp = wrap(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(8);
        pullView.setVisibility(View.GONE);
        root.addView(pullView, lp);

        hud = pill("", 22);
        hud.setVisibility(View.GONE);
        root.addView(hud, wrap(Gravity.CENTER));

        fsBar = new LinearLayout(this);
        fsBar.setOrientation(LinearLayout.HORIZONTAL);
        lockBtn = pill("🔓", 18);
        lockBtn.setOnClickListener(v -> toggleLock());
        speedBtn = pill(speedLabel(speed), 16);
        speedBtn.setOnClickListener(v -> showSpeed());
        TextView pipBtn = pill("⧉", 18);
        pipBtn.setOnClickListener(v -> enterPip());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.setMarginEnd(dp(10));
        fsBar.addView(lockBtn, bl);
        fsBar.addView(speedBtn, new LinearLayout.LayoutParams(bl));
        fsBar.addView(pipBtn, new LinearLayout.LayoutParams(bl));
        fsBar.setTag(pipBtn);
        lp = wrap(Gravity.TOP | Gravity.START);
        lp.setMargins(dp(24), dp(20), dp(24), dp(20));
        fsBar.setVisibility(View.GONE);
        root.addView(fsBar, lp);

        menuBtn = pill("⋮", 20);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(0xB3000000);
        menuBtn.setBackground(round);
        menuBtn.setPadding(0, 0, 0, 0);
        menuBtn.setAlpha(0.6f);
        menuBtn.setOnClickListener(v -> showMenu());
        lp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(menuBtn, lp);

        setContentView(root);
    }

    private void refreshOverlays() {
        if (menuBtn == null) return;
        menuBtn.setVisibility(pref(K_MENU, true) && !inFullscreen && !inPip ? View.VISIBLE : View.GONE);
    }

    private final Runnable hideFsBar = () -> fsBar.setVisibility(View.GONE);
    private final Runnable hideHud = () -> hud.setVisibility(View.GONE);

    private void showFsBar() {
        if (!inFullscreen || inPip) return;
        lockBtn.setText(locked ? "🔒" : "🔓");
        speedBtn.setVisibility(locked ? View.GONE : View.VISIBLE);
        ((View) fsBar.getTag()).setVisibility(locked || !pipSupported() ? View.GONE : View.VISIBLE);
        fsBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideFsBar);
        ui.postDelayed(hideFsBar, 3000);
    }

    private void showHud(String text) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHud);
        ui.postDelayed(hideHud, 900);
    }

    private void toggleLock() {
        locked = !locked;
        Toast.makeText(this, locked ? "Screen locked. Tap the lock to unlock." : "Unlocked",
                Toast.LENGTH_SHORT).show();
        updatePipParams();
        showFsBar();
    }

    private void seekBy(int seconds) {
        broadcast(msg("seek", "v", seconds));
        showHud(seconds > 0 ? "⏩  +" + seconds + "s" : "⏪  −" + (-seconds) + "s");
    }

    // ---------- brightness & volume ----------

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b < 0) {
            try {
                b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Exception e) {
                b = 0.5f;
            }
        }
        return clamp(b, 0.02f, 1f);
    }

    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v;
        getWindow().setAttributes(lp);
        showHud("☀  " + Math.round(v * 100) + "%");
    }

    private AudioManager audio() {
        return (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private float currentVolume() {
        AudioManager am = audio();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private void setVolume(float v) {
        AudioManager am = audio();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int level = Math.round(v * max);
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
        } catch (SecurityException ignored) {
            // Do Not Disturb can refuse volume changes
        }
        showHud("🔊  " + Math.round(100f * level / max) + "%");
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Root view: hosts the page and handles gestures on top of it. */
    private class Root extends FrameLayout {
        private static final int NONE = 0, BRIGHT = 1, VOL = 2, PULL = 3;

        final GeckoView gecko;
        private final GestureDetector taps;
        private final int slop;
        private float downX, downY, startVal;
        private int mode = NONE;
        private boolean passThrough = false;

        Root(Context c) {
            super(c);
            gecko = new GeckoView(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (inFullscreen && pref(K_GEST, true) && !locked) {
                        float w = getWidth();
                        if (e.getX() < w / 3f) seekBy(-10);
                        else if (e.getX() > w * 2f / 3f) seekBy(10);
                    }
                    return false;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (inFullscreen) showFsBar();
                    return false;
                }
            });
        }

        private boolean hits(View v, MotionEvent ev) {
            if (v.getVisibility() != VISIBLE) return false;
            Rect r = new Rect();
            v.getHitRect(r);
            return r.contains((int) ev.getX(), (int) ev.getY());
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (inPip) return false;
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                mode = NONE;
                passThrough = hits(fsBar, ev) || hits(menuBtn, ev);
            }
            if (passThrough) return false;
            if (locked) return true; // swallow everything except the lock button
            taps.onTouchEvent(ev);
            if (a == MotionEvent.ACTION_POINTER_DOWN) {
                passThrough = true; // pinch-zoom etc. belongs to the page
                return false;
            }
            if (a == MotionEvent.ACTION_MOVE && mode == NONE) {
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                int h = getHeight();
                boolean vertical = Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx) * 2;
                if (!vertical) return false;
                if (inFullscreen && pref(K_GEST, true) && downY > h * 0.08f && downY < h * 0.92f) {
                    mode = downX < getWidth() / 2f ? BRIGHT : VOL;
                    startVal = mode == BRIGHT ? currentBrightness() : currentVolume();
                    downY = ev.getY();
                    return true;
                }
                if (!inFullscreen && pref(K_PULL, true) && scrollY <= 0 && dy > 0 && downY < h * 0.35f) {
                    mode = PULL;
                    downY = ev.getY();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (locked) {
                if (a == MotionEvent.ACTION_UP) showFsBar();
                return true;
            }
            float dy = ev.getY() - downY;
            int h = Math.max(1, getHeight());
            boolean end = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
            switch (mode) {
                case BRIGHT:
                    if (a == MotionEvent.ACTION_MOVE) setBrightness(clamp(startVal - dy / h * 1.3f, 0.02f, 1f));
                    break;
                case VOL:
                    if (a == MotionEvent.ACTION_MOVE) setVolume(clamp(startVal - dy / h * 1.3f, 0f, 1f));
                    break;
                case PULL:
                    float trigger = dp(140);
                    if (a == MotionEvent.ACTION_MOVE) {
                        float pull = Math.max(0, dy);
                        pullView.setVisibility(VISIBLE);
                        pullView.setTranslationY(Math.min(pull * 0.5f, dp(90)));
                        pullView.setText(pull > trigger ? "↻  Release to refresh" : "↓  Pull to refresh");
                    } else if (end) {
                        if (a == MotionEvent.ACTION_UP && dy > trigger) {
                            session.reload();
                            pullView.setText("↻  Refreshing…");
                            ui.postDelayed(() -> pullView.setVisibility(GONE), 700);
                        } else {
                            pullView.setVisibility(GONE);
                        }
                    }
                    break;
                default:
                    break;
            }
            if (end) mode = NONE;
            return true;
        }
    }

    // ======================================================================
    // menus
    // ======================================================================

    private static String speedLabel(double s) {
        String n = (s == Math.floor(s)) ? String.valueOf((int) s) : String.valueOf(s);
        return n + "×";
    }

    private void showMenu() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add("Home");                                    actions.add(() -> session.loadUri(savedUrl()));
        items.add("Reload");                                  actions.add(() -> session.reload());
        items.add("Playback speed: " + speedLabel(speed));    actions.add(this::showSpeed);
        items.add("Colours & themes…");                       actions.add(this::showColours);
        if (pipSupported()) {
            items.add("Picture-in-picture now");              actions.add(this::enterPip);
        }
        items.add("Settings…");                               actions.add(this::showToggles);
        items.add("Change site");                             actions.add(() -> askForSite(false));
        items.add("Exit app");                                actions.add(this::finish);

        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showSpeed() {
        final double[] speeds = {0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};
        String[] labels = new String[speeds.length];
        int checked = 2;
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = speedLabel(speeds[i]);
            if (Math.abs(speeds[i] - speed) < 0.001) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Playback speed")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    speed = speeds[w];
                    broadcast(msg("speed", "v", speed));
                    speedBtn.setText(speedLabel(speed));
                    d.dismiss();
                })
                .show();
    }

    private void showToggles() {
        final String[] keys = {K_RESUME, K_PULL, K_GEST, K_PIP, K_MENU};
        String[] labels = {
                "Reopen the last page on launch",
                "Pull down to refresh",
                "Full-screen gestures (swipe for brightness / volume, double-tap to skip)",
                "Picture-in-picture when leaving the app",
                "Show the ⋮ menu button (Back on the first page also opens this menu)"
        };
        boolean[] state = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) state[i] = pref(keys[i], true);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setMultiChoiceItems(labels, state, (d, w, on) -> {
                    prefs().edit().putBoolean(keys[w], on).apply();
                    refreshOverlays();
                    updatePipParams();
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), dp(0));
        f.addView(v);
        return f;
    }

    // ---------- colours ----------

    private static final String[] PRESET_NAMES = {
            "Original", "Swap dark/light", "Night", "Dim", "Alt colours 1", "Alt colours 2", "Alt colours 3", "Warm"};
    // {invert, hue, brightness %, warmth %}
    private static final int[][] PRESETS = {
            {0, 0, 100, 0}, {1, 0, 100, 0}, {0, 0, 80, 35}, {0, 0, 70, 0},
            {0, 90, 100, 0}, {0, 180, 100, 0}, {0, 270, 100, 0}, {0, 0, 100, 25}};

    private void pushTheme() {
        broadcast(themeMsg());
    }

    private void showColours() {
        SharedPreferences p = prefs();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Changes show live on the page behind this box. Pictures and videos keep their real colours.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setAlpha(0.75f);
        box.addView(hint);

        Switch invert = new Switch(this);
        invert.setText("Swap dark and light");
        invert.setPadding(0, dp(12), 0, dp(4));

        TextView hueL = new TextView(this), dimL = new TextView(this), warmL = new TextView(this);
        SeekBar hue = new SeekBar(this), dim = new SeekBar(this), warm = new SeekBar(this);
        hue.setMax(359);
        dim.setMin(40);
        dim.setMax(100);
        warm.setMax(60);

        Runnable labels = () -> {
            hueL.setText("Colour shift: " + (hue.getProgress() == 0 ? "off" : hue.getProgress() + "°"));
            dimL.setText("Page brightness: " + dim.getProgress() + "%");
            warmL.setText("Warmth: " + (warm.getProgress() == 0 ? "off" : warm.getProgress() + "%"));
        };
        Runnable save = () -> {
            prefs().edit()
                    .putBoolean(K_INV, invert.isChecked())
                    .putInt(K_HUE, hue.getProgress())
                    .putInt(K_DIM, dim.getProgress())
                    .putInt(K_WARM, warm.getProgress())
                    .apply();
            labels.run();
            pushTheme();
        };

        // presets
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(12), 0, 0);
        hs.addView(row);
        for (int i = 0; i < PRESETS.length; i++) {
            final int[] pr = PRESETS[i];
            Button b = new Button(this);
            b.setText(PRESET_NAMES[i]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                invert.setChecked(pr[0] == 1);
                hue.setProgress(pr[1]);
                dim.setProgress(pr[2]);
                warm.setProgress(pr[3]);
                save.run();
            });
            row.addView(b);
        }
        box.addView(hs);

        invert.setChecked(p.getBoolean(K_INV, false));
        hue.setProgress(p.getInt(K_HUE, 0));
        dim.setProgress(p.getInt(K_DIM, 100));
        warm.setProgress(p.getInt(K_WARM, 0));
        labels.run();

        invert.setOnCheckedChangeListener((v, on) -> save.run());
        SeekBar.OnSeekBarChangeListener live = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) { if (fromUser) save.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { save.run(); }
        };
        hue.setOnSeekBarChangeListener(live);
        dim.setOnSeekBarChangeListener(live);
        warm.setOnSeekBarChangeListener(live);

        box.addView(invert);
        for (View v : new View[]{hueL, hue, dimL, dim, warmL, warm}) {
            if (v instanceof TextView) v.setPadding(0, dp(12), 0, 0);
            box.addView(v);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Colours & themes")
                .setView(sv)
                .setPositiveButton("Done", null)
                .setNeutralButton("Custom CSS…", (dd, w) -> showCss())
                .create();
        // keep the page visible behind the dialog so changes can be judged
        if (d.getWindow() != null) d.getWindow().setDimAmount(0.1f);
        d.show();
    }

    private void showCss() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint("/* e.g. bigger text */\nbody { font-size: 18px !important; }");
        input.setText(prefs().getString(K_CSS, ""));
        new AlertDialog.Builder(this)
                .setTitle("Custom CSS (advanced)")
                .setView(padded(input))
                .setPositiveButton("Save", (d, w) -> {
                    prefs().edit().putString(K_CSS, input.getText().toString()).apply();
                    pushTheme();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    prefs().edit().remove(K_CSS).apply();
                    pushTheme();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ======================================================================
    // back button
    // ======================================================================

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (locked) {
            showFsBar();
            Toast.makeText(this, "Screen locked. Tap the lock to unlock.", Toast.LENGTH_SHORT).show();
        } else if (inFullscreen) {
            session.exitFullScreen();
        } else if (canGoBack) {
            session.goBack();
        } else {
            showMenu();
        }
    }
}
