package com.trae.olvt.shell;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaCallback;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource;
import org.mozilla.geckoview.GeckoView;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GeckoView 壳：全屏加载本机 OLVT 前端（127.0.0.1:12393）。
 * 后端未启动时提示，可一键打开 AidLux 或重试。
 */
public class MainActivity extends Activity {

    private static final String PAGE = "http://127.0.0.1:12393/index.html?v=20261107";
    private static final String PROBE = "http://127.0.0.1:12393/";
    private static final int REQ_MIC = 1001;

    private GeckoSession session;
    private FrameLayout root;
    private LinearLayout fallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        GeckoRuntime runtime = GeckoRuntime.getDefault(this);

        session = new GeckoSession();
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onMediaPermissionRequest(GeckoSession s, String uri,
                                                 MediaSource[] video, MediaSource[] audio,
                                                 MediaCallback callback) {
                if (audio != null && audio.length > 0
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    callback.grant(null, audio[0]);
                } else {
                    callback.reject();
                }
            }
        });
        session.open(runtime);

        GeckoView view = new GeckoView(this);
        view.setSession(session);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        startWhenReady();
    }

    private void startWhenReady() {
        Thread t = new Thread(() -> {
            final boolean up = probe();
            runOnUiThread(() -> {
                if (up) {
                    if (fallback != null) {
                        root.removeView(fallback);
                        fallback = null;
                    }
                    session.loadUri(PAGE);
                } else {
                    showFallback();
                }
            });
        }, "olvt-probe");
        t.start();
    }

    private boolean probe() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(PROBE).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void showFallback() {
        if (fallback != null) {
            return;
        }
        fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setBackgroundColor(Color.BLACK);
        fallback.setPadding(48, 48, 48, 48);

        TextView tv = new TextView(this);
        tv.setText("OLVT 后端未启动\n\n请先打开 AidLux 并执行：\n"
                + "cd /home/aidlux/olvt && (setsid ./start_olvt.sh >> server.log 2>&1 < /dev/null &)");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        fallback.addView(tv);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> startWhenReady());
        fallback.addView(retry);

        Button aidlux = new Button(this);
        aidlux.setText("打开 AidLux");
        aidlux.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.aidlux");
            if (i != null) {
                startActivity(i);
            }
        });
        fallback.addView(aidlux);

        root.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    public void onBackPressed() {
        if (fallback != null) {
            super.onBackPressed();
            return;
        }
        session.goBack();
    }
}
