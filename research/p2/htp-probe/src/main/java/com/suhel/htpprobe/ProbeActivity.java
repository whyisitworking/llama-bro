package com.suhel.htpprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * P2 T1 probe. Copies the HTP skeleton from the APK's assets to noBackupFilesDir/llamabro/htp/, then runs upstream
 * llama-server in this app's process on the NPU (native/probe.c). Intent extras:
 *   args  llama-server arguments, space-separated ("{files}" = getExternalFilesDir)
 *   path  "nobackup" (default) | "nobackup+system" (system DSP paths appended, T1 Step 4.2)
 * Logs go to getExternalFilesDir()/probe.log (llama-server's stderr too), readable over adb in either build.
 */
public class ProbeActivity extends Activity {
    static { System.loadLibrary("probe"); }

    static native int run(String dspPath, String logPath, String[] args);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String files = getExternalFilesDir(null).getAbsolutePath();
        String argLine = getIntent().getStringExtra("args");
        if (argLine == null) argLine = "-m {files}/Qwen3.5-4B-Q4_K_M.gguf -dev HTP0 -ngl 99 -c 4096 -np 1 --host 127.0.0.1 --port 8099";
        String mode = getIntent().getStringExtra("path");
        String[] args = ("llama-server " + argLine.replace("{files}", files)).split(" ");
        String log = files + "/probe.log";
        new Thread(() -> {
            try {
                File dir = new File(getNoBackupFilesDir(), "llamabro/htp");
                dir.mkdirs();
                for (String name : getAssets().list("htp")) {
                    File tmp = new File(dir, name + ".tmp");
                    try (InputStream in = getAssets().open("htp/" + name); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    }
                    if (!tmp.renameTo(new File(dir, name))) throw new IllegalStateException("rename " + tmp);
                }
                String dsp = dir.getAbsolutePath();
                if ("nobackup+system".equals(mode)) dsp += ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp";
                Log.i("htp-probe", "skeleton dir " + dsp + ", debuggable=" + ((getApplicationInfo().flags & 2) != 0)
                        + ", nativeLibraryDir=" + getApplicationInfo().nativeLibraryDir);
                int rc = run(dsp, log, args);
                Log.i("htp-probe", "llama_server returned " + rc);
            } catch (Throwable t) {
                Log.e("htp-probe", "probe failed", t);
            }
        }, "llama-server").start();
    }
}
