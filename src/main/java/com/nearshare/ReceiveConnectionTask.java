package com.nearshare;

import javafx.application.Platform;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Handles a single already-accepted incoming connection: reads the file
 * name + optional PIN + size, checks the PIN against the app's settings
 * (if one is required), acknowledges, then streams the file to disk.
 * One of these is created per connection and run on the shared thread
 * pool, so several can be in flight at once.
 */
public class ReceiveConnectionTask extends AbstractTransferTask {

    private final Socket socket;

    public ReceiveConnectionTask(MainController controller, DatabaseManager db, Socket socket) {
        super(controller, db);
        this.socket = socket;
    }

    @Override
    protected void executeTransfer() throws IOException {
        String peerIp = socket.getInetAddress().getHostAddress();
        Platform.runLater(() -> controller.setStatus("Incoming connection from " + peerIp + "...", MainController.StatusType.BUSY));

        try (DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            String fileName = dis.readUTF();
            String pinHash = dis.readUTF();
            long fileSize = dis.readLong();

            String requiredPinHash = db.getSetting("pin_hash");
            boolean pinRequired = requiredPinHash != null && !requiredPinHash.isBlank();

            if (pinRequired && !requiredPinHash.equals(pinHash)) {
                dos.writeUTF("REJECTED");
                dos.flush();
                Platform.runLater(() -> controller.setStatus(
                        "Blocked transfer from " + peerIp + " (incorrect PIN).", MainController.StatusType.ERROR));
                return;
            }

            dos.writeUTF("OK");
            dos.flush();

            Platform.runLater(() -> controller.setStatus("Receiving \"" + fileName + "\" from " + peerIp + "...", MainController.StatusType.BUSY));

            File downloadDir = new File(System.getProperty("user.home"), "NearShare_Downloads");
            if (!downloadDir.exists()) downloadDir.mkdirs();
            File outFile = new File(downloadDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                long remaining = fileSize;
                int read;
                while (remaining > 0 && (read = dis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            HistoryEntry entry = new HistoryEntry(fileName, "Received", peerIp, fileSize, timestamp, elapsedFormatted());
            controller.recordHistory(entry);

            Platform.runLater(() -> controller.setStatus("\u2713 Received \"" + fileName + "\" from " + peerIp, MainController.StatusType.ACTIVE));
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    protected void onError(IOException e) {
        Platform.runLater(() -> controller.setStatus("Failed to receive file: " + e.getMessage(), MainController.StatusType.ERROR));
    }
}
