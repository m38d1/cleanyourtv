package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.net.InetAddress;
import java.net.Socket;
import java.util.function.IntConsumer;

/**
 * Local build: the round-trip time shown in the network pill. One TCP connect to Google every ten
 * seconds, timed, then dropped, so it needs no ICMP permission and says the same thing a ping does:
 * how long the link (and, while a VPN is up, its exit) takes to answer.
 */
final class WifiPing {

    private static final long INTERVAL_MS = 10 * 1000L;
    private static final long RETRY_MS = 5 * 1000L;
    private static final int TIMEOUT_MS = 2000;
    private static final String HOST = "www.google.com";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private volatile Handler bg;
    private volatile int last = -1;
    private InetAddress target;

    /** Reports the cached reading at once, then a fresh one every ten seconds while Home is resumed. */
    void start(IntConsumer sink) {
        if (last >= 0) sink.accept(last);
        if (thread != null) return;
        thread = new HandlerThread("ping");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                int ms = probe();
                last = ms;
                if (ms >= 0) main.post(() -> {
                    if (bg == h) sink.accept(ms);
                });
                if (bg == h) h.postDelayed(this, ms >= 0 ? INTERVAL_MS : RETRY_MS);
            }
        });
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /** The connect time in ms, or -1 when the link did not answer. */
    private int probe() {
        try {
            if (target == null) target = InetAddress.getByName(HOST);
            long t0 = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new java.net.InetSocketAddress(target, 443), TIMEOUT_MS);
            }
            long us = (System.nanoTime() - t0) / 1000;
            return (int) Math.max(1, us / 1000);
        } catch (Exception e) {
            target = null;
            return -1;
        }
    }
}
