package com.nearshare;

import javafx.application.Platform;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans the local /24 subnet (up to 254 addresses) for other machines with
 * NearShare's receiver port open. This is a second, explicit demonstration
 * of a Thread Pool: rather than opening 254 sockets one after another
 * (which would take a long time), a fixed pool of worker threads probes
 * many addresses at once and reports back through DeviceFoundListener.
 */
public class LanScannerService {

    private static final int SCAN_THREADS = 40;
    private static final int CONNECT_TIMEOUT_MS = 150;

    private final DatabaseManager db;

    public LanScannerService(DatabaseManager db) {
        this.db = db;
    }

    /** Kicks off an asynchronous subnet scan; results stream back via the listener. */
    public void scanAsync(String myIp, int port, DeviceFoundListener listener) {
        Thread orchestrator = new Thread(() -> runScan(myIp, port, listener));
        orchestrator.setDaemon(true);
        orchestrator.start();
    }

    private void runScan(String myIp, int port, DeviceFoundListener listener) {
        String prefix = NetworkUtils.getSubnetPrefix(myIp);
        if (prefix == null) {
            Platform.runLater(() -> listener.onScanFinished(0));
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        CountDownLatch latch = new CountDownLatch(254);
        AtomicInteger found = new AtomicInteger(0);

        for (int host = 1; host <= 254; host++) {
            String candidateIp = prefix + host;
            pool.submit(() -> {
                try {
                    if (!candidateIp.equals(myIp) && isPortOpen(candidateIp, port)) {
                        found.incrementAndGet();
                        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                        int deviceId = db.upsertDevice(candidateIp, null, timestamp);
                        Device device = new Device(deviceId, "Device @ " + candidateIp, candidateIp, timestamp, "Online");
                        Platform.runLater(() -> listener.onDeviceFound(device));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
            int total = found.get();
            Platform.runLater(() -> listener.onScanFinished(total));
        }
    }

    private boolean isPortOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
