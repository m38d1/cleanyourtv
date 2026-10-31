package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * vpn_toggle_activity alone cannot bring Hiddify's tunnel up: its ShortcutActivity starts the background service
 * but, unlike Hiddify's own quick-settings tile, never sets startCoreAfterStartingService, so the core stays down.
 * The service's core does listen on 127.0.0.1:17079 (vpn_daemon_port) for gRPC, though, and an empty
 * hcore.Core/Start request starts the tunnel from the config the core started last, the same one Hiddify's own
 * Start button sends. Blocking; call off the main thread.
 */
final class VpnDaemon {
    private static final String TAG = "VpnDaemon";
    private static final MediaType GRPC = MediaType.parse("application/grpc");
    private static final long TUNNEL_TIMEOUT_MS = 8000;

    private VpnDaemon() { }

    /** Any network with the VPN transport, Wireshark-style kill-switch tunnels included. */
    static boolean vpnUp(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    /**
     * Brings the service up through toggle when its daemon is not listening yet, then asks the core to start, and
     * waits for the tunnel itself: the gRPC reply only says the request was accepted, the core reports the outcome
     * on its status stream. False when the tunnel does not appear, so the caller can fall back to opening the app.
     */
    static boolean start(Context ctx, Intent toggle, int port) {
        if (vpnUp(ctx)) return true;
        boolean up = waitForPort(port, 0);
        Log.d(TAG, "start: port " + port + (up ? " already open" : " closed"));
        if (!up) {
            Context app = ctx.getApplicationContext();
            app.startActivity(new Intent(toggle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!waitForPort(port, 4000)) {
                Log.w(TAG, "service did not open port " + port);
                return false;
            }
        }
        if (!startCore(port)) return false;
        long deadline = System.currentTimeMillis() + TUNNEL_TIMEOUT_MS;
        do {
            if (vpnUp(ctx)) {
                Log.d(TAG, "start: tunnel up");
                return true;
            }
            if (!sleep(200)) break;
        } while (System.currentTimeMillis() < deadline);
        Log.w(TAG, "start: tunnel did not come up");
        return false;
    }

    private static boolean waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                return true;
            } catch (IOException ignored) {
            }
            if (!sleep(100)) return false;
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** hcore.Core/Start over HTTP/2 prior knowledge (the core speaks h2c), with an empty StartRequest. */
    private static boolean startCore(int port) {
        // gRPC frame: one compression-flag byte, then the message length big-endian, then the protobuf message.
        byte[] body = new byte[5];
        OkHttpClient client = new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + port + "/hcore.Core/Start")
                .header("te", "trailers")
                .header("grpc-accept-encoding", "identity")
                .post(RequestBody.create(GRPC, body))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "start: HTTP " + response.code());
                return false;
            }
            // A rejected request answers with the whole reply in one headers frame carrying grpc-status, so the
            // status can be there as well as in the trailers; read the body out first for the trailers to arrive.
            ResponseBody payload = response.body();
            if (payload != null) payload.bytes();
            String status = response.header("grpc-status");
            String message = response.header("grpc-message");
            if (status == null) {
                status = response.trailers().get("grpc-status");
                message = response.trailers().get("grpc-message");
            }
            if (status != null && !"0".equals(status)) {
                Log.w(TAG, "start: grpc-status " + status + " " + message);
                return false;
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "start: " + e);
            return false;
        }
    }
}
