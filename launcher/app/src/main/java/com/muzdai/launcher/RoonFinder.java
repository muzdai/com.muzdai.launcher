package com.muzdai.launcher;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds the Roon Core (its web display answers on port 9330) and an Eversolo (its control API on port 9529) among
 * the addresses of the TV's own /24 network. Blocking, a few seconds.
 */
final class RoonFinder {
    private RoonFinder() { }

    private static final int CONNECT_MS = 400;

    /** {Roon Core address, Eversolo address}, null where none was found. */
    static String[] find() {
        String prefix = prefix();
        String[] found = new String[2];
        if (prefix == null) return found;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            String ip = prefix + i;
            pool.execute(() -> {
                if (found[0] == null && open(ip, RoonApi.PORT) && isRoon(ip)) {
                    synchronized (found) {
                        if (found[0] == null) found[0] = ip;
                    }
                }
                if (found[1] == null && open(ip, Eversolo.PORT) && Eversolo.answers(ip)) {
                    synchronized (found) {
                        if (found[1] == null) found[1] = ip;
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // what was found so far
        }
        return found;
    }

    /** "192.168.1." of the TV's first private IPv4 address. */
    private static String prefix() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String s = a.getHostAddress();
                        return s.substring(0, s.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return null;
    }

    private static boolean open(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), CONNECT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The Roon Core serves its web display at /display/. */
    private static boolean isRoon(String ip) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + RoonApi.PORT + "/display/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            try {
                return c.getResponseCode() == 200;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
