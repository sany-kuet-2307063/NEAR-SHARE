package com.nearshare;

import javafx.application.Platform;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

/**
 * Runs continuously on its own daemon thread, listening for incoming
 * connections. Every accepted connection is handed to the shared
 * ExecutorService thread pool as a ReceiveConnectionTask, so multiple
 * incoming files can be received concurrently instead of blocking one
 * another (this is the Concurrency / Thread Pool requirement in action).
 */
public class ReceiverServerTask implements Runnable {

    private final MainController controller;
    private final DatabaseManager db;
    private final ExecutorService transferPool;
    private final int port;

    public ReceiverServerTask(MainController controller, DatabaseManager db, ExecutorService transferPool, int port) {
        this.controller = controller;
        this.db = db;
        this.transferPool = transferPool;
        this.port = port;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Platform.runLater(() -> controller.setStatus(
                    "Listening for incoming files on port " + port + "...", MainController.StatusType.ACTIVE));
            while (true) {
                Socket socket = serverSocket.accept();
                // Hand off to the thread pool immediately so accept() can loop again right away.
                transferPool.submit(new ReceiveConnectionTask(controller, db, socket));
            }
        } catch (IOException e) {
            Platform.runLater(() -> controller.setStatus("Network error: " + e.getMessage(), MainController.StatusType.ERROR));
        }
    }
}
