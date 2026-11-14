package io.freemytv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ports of "Wireless debugging": they change every time it's turned on, and the TV announces them on the local
 * network (mDNS), as a computer's adb finds them. Only this TV's own announcements count (a phone nearby announces
 * its own). Blocks for up to `waitMs`: never on the UI thread.
 */
final class AdbPorts {
    static final String CONNECT = "_adb-tls-connect._tcp", PAIRING = "_adb-tls-pairing._tcp";

    private AdbPorts() {}

    /** The port of this TV's `type` service, or 0 when none showed up in time. */
    static int find(Context c, String type, long waitMs) {
        NsdManager nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return 0;
        Set<String> mine = localAddresses();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger port = new AtomicInteger();
        Object resolving = new Object();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) {
                synchronized (resolving) {
                    try {
                        nsd.resolveService(info, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo i, int error) {}
                            @Override public void onServiceResolved(NsdServiceInfo i) {
                                InetAddress host = i.getHost();
                                if (host != null && (mine.contains(host.getHostAddress()) || host.isLoopbackAddress())) {
                                    port.set(i.getPort());
                                    done.countDown();
                                }
                            }
                        });
                    } catch (RuntimeException ignored) {
                        // One resolve at a time on older Android: the next announcement tries again.
                    }
                }
            }
            @Override public void onStartDiscoveryFailed(String t, int error) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int error) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | RuntimeException e) {
            AppLog.w("adb ports: " + e.getMessage());
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
        return port.get();
    }

    /**
     * Wireless debugging listens on the TV's network address, not on 127.0.0.1: a socket to its port on the first of
     * them that answers (the loopback first, for TVs that also listen there).
     */
    static java.net.Socket connect(int port, int timeoutMs) throws java.io.IOException {
        java.util.List<String> hosts = new java.util.ArrayList<>();
        hosts.add("127.0.0.1");
        for (String a : localAddresses()) if (a.indexOf(':') < 0 && !a.startsWith("127.")) hosts.add(a);   // IPv4 only
        java.io.IOException last = null;
        for (String host : hosts) {
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
                return s;
            } catch (java.io.IOException e) {
                last = e;
                try { s.close(); } catch (java.io.IOException ignored) {}
            }
        }
        throw last != null ? last : new java.io.IOException("no address");
    }

    /** The TV's own addresses (Wi-Fi and Ethernet). */
    private static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(n.getInetAddresses())) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
