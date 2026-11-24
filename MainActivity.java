package com.shadowxploit.aquarium;

import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    // Kali NetHunter minimal rootfs (arm64). Semak URL ni kalau download gagal.
    static final String ROOTFS_URL = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz";
    static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    WebView wv;
    final ConcurrentHashMap<String, Process> procs = new ConcurrentHashMap<>();
    final AtomicBoolean setupRunning = new AtomicBoolean(false);
    volatile boolean cancel = false;
    volatile String setupId = "";

    File prootDir() { return new File(getFilesDir(), "proot"); }
    File kaliDir() { return new File(getFilesDir(), "kali"); }
    File rootfs() { return new File(kaliDir(), "rootfs"); }
    boolean isReady() { return new File(kaliDir(), ".ready").exists() && new File(prootDir(), "bin/proot").exists(); }

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

    void out(String id, String s) {
        send("__out", id, Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    class Bridge {
        @JavascriptInterface public String home() { return isReady() ? "/root" : getFilesDir().getAbsolutePath(); }
        @JavascriptInterface public boolean ready() { return isReady(); }

        @JavascriptInterface public void exec(final String id, final String cmd, final String cwd) {
            new Thread(() -> {
                try {
                    if (cmd.trim().equals("kali-setup")) {
                        setup(id);
                    } else if (isReady()) {
                        runGuest(id, cmd, cwd);
                    } else {
                        String script = "cd '" + cwd.replace("'", "'\\''") + "' 2>/dev/null; " + cmd + "\necho \"@@CWD@@$(pwd)\"";
                        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", script);
                        pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                        pb.environment().put("TERM", "dumb");
                        stream(id, pb);
                    }
                } catch (Exception e) {
                    out(id, "error: " + e);
                }
                procs.remove(id);
                send("__done", id, "");
            }).start();
        }

        @JavascriptInterface public void kill(String id) {
            if (id.equals(setupId)) cancel = true;
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

    /* ── jalankan command dalam Kali (proot) ── */
    void runGuest(String id, String cmd, String cwd) throws Exception {
        String gcwd = (cwd != null && cwd.startsWith("/") && new File(rootfs(), cwd).isDirectory()) ? cwd : "/root";
        File tmp = new File(kaliDir(), "tmp");
        tmp.mkdirs();
        List<String> c = new ArrayList<>();
        c.add(new File(prootDir(), "bin/proot").getPath());
        c.add("-0"); c.add("--link2symlink"); c.add("--kill-on-exit");
        c.add("-r"); c.add(rootfs().getPath());
        c.add("-b"); c.add("/dev"); c.add("-b"); c.add("/proc"); c.add("-b"); c.add("/sys");
        c.add("-w"); c.add(gcwd);
        c.add("/usr/bin/env"); c.add("-i");
        c.add("HOME=/root"); c.add("TERM=dumb"); c.add("LANG=C.UTF-8");
        c.add("DEBIAN_FRONTEND=noninteractive"); c.add("PATH=" + GUEST_PATH);
        c.add("/bin/bash"); c.add("-c");
        c.add(cmd + "\necho \"@@CWD@@$(pwd)\"");
        ProcessBuilder pb = new ProcessBuilder(c);
        pb.environment().put("PROOT_TMP_DIR", tmp.getPath());
        pb.environment().put("PROOT_LOADER", new File(prootDir(), "libexec/proot/loader").getPath());
        File l32 = new File(prootDir(), "libexec/proot/loader32");
        if (l32.exists()) pb.environment().put("PROOT_LOADER_32", l32.getPath());
        pb.environment().put("LD_LIBRARY_PATH", new File(prootDir(), "lib").getPath());
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        stream(id, pb);
    }

    void stream(String id, ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        procs.put(id, p);
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) send("__out", id, Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
        p.waitFor();
    }

    /* ── kali-setup: pasang proot + download Kali rootfs ── */
    void setup(String id) {
        if (!setupRunning.compareAndSet(false, true)) { out(id, "setup sedang berjalan"); return; }
        cancel = false;
        setupId = id;
        File part = new File(kaliDir(), "rootfs.part");
        try {
            if (getFilesDir().getUsableSpace() < 1500L * 1024 * 1024) { out(id, "Storan tak cukup (perlu ~1.5GB kosong)"); return; }
            out(id, "[1/3] Memasang proot...");
            File pd = prootDir();
            deleteRec(pd);
            try (InputStream in = getAssets().open("proot-aarch64.tar")) { extractTar(in, pd, null, false, null); }
            File pb = new File(pd, "bin/proot");
            if (!pb.exists()) throw new IOException("proot tiada dalam bundle");
            Os.chmod(pb.getPath(), 0755);
            File[] ld = new File(pd, "libexec/proot").listFiles();
            if (ld != null) for (File f : ld) Os.chmod(f.getPath(), 0755);

            out(id, "[2/3] Muat turun + extract Kali (boleh ambil beberapa minit)...");
            kaliDir().mkdirs();
            deleteRec(part);
            part.mkdirs();
            HttpURLConnection hc = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
            hc.setConnectTimeout(20000);
            hc.setReadTimeout(30000);
            hc.setInstanceFollowRedirects(true);
            hc.connect();
            if (hc.getResponseCode() != 200) throw new IOException("HTTP " + hc.getResponseCode() + " dari " + ROOTFS_URL);
            final long total = hc.getContentLengthLong();
            final Counting cin = new Counting(new BufferedInputStream(hc.getInputStream(), 1 << 16));
            final long[] last = {-1};
            Runnable prog = () -> {
                long mb = cin.n >> 20;
                long step = total > 0 ? (cin.n * 100 / total) / 5 : mb / 10;
                if (step != last[0]) {
                    last[0] = step;
                    out(id, total > 0 ? ("  " + (cin.n * 100 / total) + "%  (" + mb + "/" + (total >> 20) + " MB)") : ("  " + mb + " MB"));
                }
            };
            try (InputStream xz = new BufferedInputStream(new XZInputStream(cin), 1 << 16)) {
                extractTar(xz, part, "kali-arm64/", true, prog);
            }

            out(id, "[3/3] Konfigurasi...");
            File root = rootfs();
            deleteRec(root);
            if (!part.renameTo(root)) throw new IOException("gagal rename rootfs");
            File rc = new File(root, "etc/resolv.conf");
            rc.delete();
            writeFile(rc, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
            new File(root, "etc/apt/apt.conf.d").mkdirs();
            writeFile(new File(root, "etc/apt/apt.conf.d/01-proot"), "APT::Sandbox::User \"root\";\n");
            new File(root, "root").mkdirs();
            new File(root, "tmp").mkdirs();
            new File(kaliDir(), "tmp").mkdirs();
            new File(kaliDir(), ".ready").createNewFile();
            out(id, "Siap! Kali dah dipasang. Cuba: cat /etc/os-release\nNota: command interaktif (tanya Y/n) tak disokong, guna 'apt install -y ...'");
            out(id, "@@CWD@@/root");
        } catch (Throwable e) {
            out(id, "GAGAL: " + e);
            deleteRec(part);
        } finally {
            setupRunning.set(false);
            setupId = "";
        }
    }

    static class Counting extends FilterInputStream {
        volatile long n = 0;
        Counting(InputStream i) { super(i); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) n++; return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int r = super.read(b, o, l); if (r > 0) n += r; return r; }
    }

    static void writeFile(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    static void deleteRec(File f) {
        if (f == null || !(f.exists() || Files.isSymbolicLink(f.toPath()))) return;
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) deleteRec(x);
        }
        f.delete();
    }

    static String strip(String name, String prefix) {
        if (prefix != null && name.startsWith(prefix)) name = name.substring(prefix.length());
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        return name;
    }

    void extractTar(InputStream raw, File dest, String prefix, boolean skipDev, Runnable prog) throws Exception {
        dest.mkdirs();
        String destCanon = dest.getCanonicalPath();
        try (TarArchiveInputStream tin = new TarArchiveInputStream(raw)) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = tin.getNextTarEntry()) != null) {
                if (cancel) throw new IOException("dibatalkan");
                if (prog != null) prog.run();
                String name = strip(e.getName(), prefix);
                if (name.isEmpty() || name.equals(".")) continue;
                if (name.equals("..") || name.startsWith("../") || name.contains("/../")) continue;
                if (skipDev && (name.equals("dev") || name.startsWith("dev/"))) { if (name.equals("dev")) new File(dest, "dev").mkdirs(); continue; }
                File f = new File(dest, name);
                File par = f.getParentFile();
                par.mkdirs();
                if (!par.getCanonicalPath().startsWith(destCanon)) continue;
                int mode = e.getMode() & 07777;
                if (e.isDirectory()) {
                    f.mkdirs();
                    Os.chmod(f.getPath(), mode | 0700);
                } else if (e.isSymbolicLink()) {
                    deleteRec(f);
                    Os.symlink(e.getLinkName(), f.getPath());
                } else if (e.isLink()) {
                    deleteRec(f);
                    File src = new File(dest, strip(e.getLinkName(), prefix));
                    try { Os.link(src.getPath(), f.getPath()); }
                    catch (Exception ex) { copy(src, f); }
                } else if (e.isFile()) {
                    deleteRec(f);
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        int r;
                        while ((r = tin.read(buf)) > 0) o.write(buf, 0, r);
                    }
                    Os.chmod(f.getPath(), mode | 0600);
                }
            }
        }
    }

    static void copy(File a, File b) throws IOException {
        try (InputStream i = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = i.read(buf)) > 0) o.write(buf, 0, r);
        }
    }

    void send(String fn, String id, String d) {
        runOnUiThread(() -> wv.evaluateJavascript("window." + fn + "('" + id + "','" + d + "')", null));
    }
}
