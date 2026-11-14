// SPDX-License-Identifier: GPL-3.0-only
// Injects touch gestures on a device or emulator from adb shell, with real timing.
// Build: tools/swipe-inject/build.sh <outdir>
// Run:   adb shell CLASSPATH=/data/local/tmp/inj.dex app_process / Inj /data/local/tmp/g.txt [stepMs]
// Input lines: "D x y" (down), "M x y" (move), "U x y" (up), "W ms" (wait).
// Every D/M/U is followed by a pause of stepMs (default 8 ms, about 120 Hz).
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

public class Inj {
    private static Object manager;
    private static Method inject;

    private static void init() throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException e) {
            cls = Class.forName("android.hardware.input.InputManager");
        }
        manager = cls.getDeclaredMethod("getInstance").invoke(null);
        inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static void send(long down, int action, float x, float y) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(down, now, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject.invoke(manager, e, 0);
        e.recycle();
    }

    public static void main(String[] args) throws Exception {
        init();
        long down = 0;
        int step = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 0 || p[0].isEmpty()) continue;
                switch (p[0]) {
                    case "W":
                        Thread.sleep(Long.parseLong(p[1]));
                        continue;
                    case "D":
                        down = SystemClock.uptimeMillis();
                        send(down, MotionEvent.ACTION_DOWN, Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                        break;
                    case "M":
                        send(down, MotionEvent.ACTION_MOVE, Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                        break;
                    case "U":
                        send(down, MotionEvent.ACTION_UP, Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                        break;
                    default:
                        continue;
                }
                Thread.sleep(step);
            }
        }
    }
}
