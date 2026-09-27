package com.nearshare;

import javafx.application.Platform;

import java.io.IOException;

/**
 * Abstract superclass for anything that performs ONE file transfer over a
 * socket (as opposed to ReceiverServerTask, which just runs the accept()
 * loop forever). SenderTask and ReceiveConnectionTask both extend this:
 * they share timing, error handling and status-reporting, and only differ
 * in the direction bytes flow, which is left to the abstract
 * executeTransfer() method.
 */
public abstract class AbstractTransferTask implements Runnable {

    protected final MainController controller;
    protected final DatabaseManager db;
    protected long startTime;

    protected AbstractTransferTask(MainController controller, DatabaseManager db) {
        this.controller = controller;
        this.db = db;
    }

    @Override
    public final void run() {
        startTime = System.currentTimeMillis();
        Platform.runLater(() -> controller.setTransferActive(true));
        try {
            executeTransfer();
        } catch (IOException e) {
            onError(e);
        } finally {
            Platform.runLater(() -> controller.setTransferActive(false));
        }
    }

    /** Subclasses implement the actual send or receive logic here. */
    protected abstract void executeTransfer() throws IOException;

    /** Subclasses report the failure in their own words (different message for send vs receive). */
    protected abstract void onError(IOException e);

    protected String elapsedFormatted() {
        return formatDuration(System.currentTimeMillis() - startTime);
    }

    public static String formatDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        return String.format("%.1f s", ms / 1000.0);
    }
}
