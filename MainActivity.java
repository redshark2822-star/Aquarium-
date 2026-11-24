package com.shadowxploit.aquarium;

import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    WebView wv;
    final ConcurrentHashMap<String, Process> procs = new ConcurrentHashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        wv = new WebView(this);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        wv.setBackgroundColor(0xFF000000);
        wv.addJavascriptInterface(new Bridge(), "Android");
        wv.loadUrl("file:///android_asset/index.html");
    }

    static int pidOf(Process p) {
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) { return -1; }
    }

    class Bridge {
        @JavascriptInterface public String home() { return getFilesDir().getAbsolutePath(); }

        @JavascriptInterface public void exec(final String id, final String cmd, final String cwd) {
            new Thread(() -> {
                try {
                    String script = "cd '" + cwd.replace("'", "'\\''") + "' 2>/dev/null; " + cmd + "\necho \"@@CWD@@$(pwd)\"";
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", script);
                    pb.redirectErrorStream(true);
                    pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                    pb.environment().put("TERM", "dumb");
                    Process p = pb.start();
                    procs.put(id, p);
                    InputStream in = p.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) send("__out", id, Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
                    p.waitFor();
                } catch (Exception e) {
                    send("__out", id, Base64.encodeToString(("error: " + e).getBytes(), Base64.NO_WRAP));
                }
                procs.remove(id);
                send("__done", id, "");
            }).start();
        }

        @JavascriptInterface public void kill(String id) {
            Process p = procs.get(id);
            if (p == null) return;
            int pid = pidOf(p);
            if (pid > 0) {
                try {
                    String k = "pkill -9 -P " + pid + " 2>/dev/null || { ps -A -o PID,PPID | while read a b; do [ \"$b\" = " + pid + " ] && kill -9 $a; done; }; kill -9 " + pid;
                    Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", k}).waitFor();
                } catch (Exception e) { }
            }
            p.destroy();
        }
    }

    void send(String fn, String id, String d) {
        runOnUiThread(() -> wv.evaluateJavascript("window." + fn + "('" + id + "','" + d + "')", null));
    }
}
