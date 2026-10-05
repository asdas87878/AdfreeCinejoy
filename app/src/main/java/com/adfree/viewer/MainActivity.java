package com.adfree.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/**
 * Full-screen single-site viewer: Firefox's engine (GeckoView) with uBlock Origin built in.
 * No address bar, no tabs. Pop-ups and redirects to other sites are blocked.
 */
public class MainActivity extends Activity {
    private static final String TAG = "AdFreeViewer";
    private static final String PREFS = "settings";
    private static final String KEY_URL = "start_url";
    private static final String UBLOCK_URI = "resource://android/assets/ublock/";
    private static final String UBLOCK_ID = "uBlock0@raymondhill.net";

    // One engine per app process.
    private static GeckoRuntime sRuntime;

    private GeckoSession session;
    private boolean canGoBack = false;
    private boolean inFullscreen = false;
    private String siteHost = null;

    private static GeckoRuntime runtime(Activity a) {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    .build();
            sRuntime = GeckoRuntime.create(a.getApplicationContext(), settings);
            sRuntime.getWebExtensionController()
                    .ensureBuiltIn(UBLOCK_URI, UBLOCK_ID)
                    .accept(
                            ext -> Log.i(TAG, "uBlock Origin ready"),
                            e -> Log.e(TAG, "uBlock Origin failed to load", e));
        }
        return sRuntime;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        GeckoView view = new GeckoView(this);
        setContentView(view);

        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build());
        session.setNavigationDelegate(navigation);
        session.setContentDelegate(content);
        session.open(runtime(this));
        view.setSession(session);

        String url = savedUrl();
        if (url.isEmpty()) {
            askForSite(true);
        } else {
            openSite(url);
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.close();
        super.onDestroy();
    }

    // ---------- site settings ----------

    private String savedUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String url = p.getString(KEY_URL, null);
        return url != null ? url : BuildConfig.START_URL;
    }

    private void openSite(String url) {
        siteHost = baseHost(Uri.parse(url).getHost());
        session.loadUri(url);
    }

    private void askForSite(boolean firstRun) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("example.com");
        input.setText(savedUrl());

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Which site should this app open?")
                .setView(input)
                .setPositiveButton("Open", (d, w) -> {
                    String url = normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        askForSite(firstRun);
                        return;
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                    openSite(url);
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

    private boolean isSameSite(String host) {
        if (siteHost == null || host == null) return true;
        host = baseHost(host);
        return host.equals(siteHost) || host.endsWith("." + siteHost) || siteHost.endsWith("." + host);
    }

    // ---------- navigation: block pop-ups and redirects to other sites ----------

    private final GeckoSession.NavigationDelegate navigation = new GeckoSession.NavigationDelegate() {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            Uri uri = Uri.parse(request.uri);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

            // Internal pages (including uBlock's own "blocked" page) are fine.
            if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("moz-extension")
                    || scheme.equals("resource") || scheme.equals("data")) {
                return allow();
            }
            // intent:, market:, tel: etc. — usually ads trying to open other apps.
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return deny();
            }
            if (!isSameSite(uri.getHost())) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Blocked: " + uri.getHost(), Toast.LENGTH_SHORT).show());
                return deny();
            }
            // Same-site link that wants a new window: open it here instead.
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

    // ---------- full-screen video, crashes ----------

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
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    // ---------- back button ----------

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (inFullscreen) {
            session.exitFullScreen();
        } else if (canGoBack) {
            session.goBack();
        } else {
            new AlertDialog.Builder(this)
                    .setItems(new String[]{"Exit app", "Change site", "Reload"}, (d, which) -> {
                        if (which == 0) finish();
                        else if (which == 1) askForSite(false);
                        else session.reload();
                    })
                    .show();
        }
    }
}
