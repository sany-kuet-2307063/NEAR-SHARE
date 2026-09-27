package com.nearshare;

import javafx.application.Platform;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Sends a single file to the given target IP address. Runs on the shared
 * transfer thread pool (see MainController), not a raw new Thread.
 */
public class SenderTask extends AbstractTransferTask {

    private final String targetIp;
    private final File file;
    private final int port;
    private final String pinPlainText; // entered by the user, hashed before it ever touches the socket

    public SenderTask(String targetIp, File file, MainController controller, DatabaseManager db,
                       int port, String pinPlainText) {
        super(controller, db);
        this.targetIp = targetIp;
        this.file = file;
        this.port = port;
        this.pinPlainText = pinPlainText == null ? "" : pinPlainText;
    }

    @Override
    protected void executeTransfer() throws IOException {
        Platform.runLater(() -> controller.setStatus("Connecting to " + targetIp + "...", MainController.StatusType.BUSY));

        try (Socket socket = new Socket(targetIp, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(file)) {

            String pinHash = DatabaseManager.hashPin(pinPlainText);

            dos.writeUTF(file.getName());
            dos.writeUTF(pinHash);
            dos.writeLong(file.length());
            dos.flush();

            String ack = dis.readUTF();
            if (!"OK".equals(ack)) {
                Platform.runLater(() -> controller.setStatus(
                        "Transfer rejected by " + targetIp + " (wrong or missing PIN).",
                        MainController.StatusType.ERROR));
                return;
            }

            Platform.runLater(() -> controller.setStatus("Sending \"" + file.getName() + "\" to " + targetIp + "...", MainController.StatusType.BUSY));

            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) > 0) {
                dos.write(buffer, 0, read);
            }
            dos.flush();

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            HistoryEntry entry = new HistoryEntry(file.getName(), "Sent", targetIp, file.length(), timestamp, elapsedFormatted());
            controller.recordHistory(entry);

            Platform.runLater(() -> controller.setStatus("\u2713 Sent \"" + file.getName() + "\" successfully!", MainController.StatusType.ACTIVE));
        }
    }

    @Override
    protected void onError(IOException e) {
        Platform.runLater(() -> controller.setStatus("Failed to send to " + targetIp + ": " + e.getMessage(), MainController.StatusType.ERROR));
    }
}
