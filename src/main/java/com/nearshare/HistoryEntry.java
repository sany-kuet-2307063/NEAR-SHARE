package com.nearshare;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;

/**
 * Represents a single row of transfer history (a file that was either
 * sent or received), shown in the history table and stored in the
 * "history" table of the SQLite database. Each entry optionally links to
 * a Device row via deviceId (history.device_id -> devices.id).
 */
public class HistoryEntry {

    private final SimpleIntegerProperty id;
    private final SimpleStringProperty fileName;
    private final SimpleStringProperty direction;
    private final SimpleStringProperty peerIp;
    private final SimpleLongProperty sizeBytes;
    private final SimpleStringProperty timestamp;
    private final SimpleStringProperty duration;
    private final SimpleStringProperty note;
    private int deviceId;

    public HistoryEntry(int id, String fileName, String direction, String peerIp,
                         long sizeBytes, String timestamp, String duration,
                         String note, int deviceId) {
        this.id = new SimpleIntegerProperty(id);
        this.fileName = new SimpleStringProperty(fileName);
        this.direction = new SimpleStringProperty(direction);
        this.peerIp = new SimpleStringProperty(peerIp);
        this.sizeBytes = new SimpleLongProperty(sizeBytes);
        this.timestamp = new SimpleStringProperty(timestamp);
        this.duration = new SimpleStringProperty(duration);
        this.note = new SimpleStringProperty(note == null ? "" : note);
        this.deviceId = deviceId;
    }

    /** Convenience constructor used when a brand new transfer just finished (no id/note yet). */
    public HistoryEntry(String fileName, String direction, String peerIp,
                         long sizeBytes, String timestamp, String duration) {
        this(0, fileName, direction, peerIp, sizeBytes, timestamp, duration, "", 0);
    }

    public int getId() { return id.get(); }
    public String getFileName() { return fileName.get(); }
    public String getDirection() { return direction.get(); }
    public String getPeerIp() { return peerIp.get(); }
    public long getSizeBytes() { return sizeBytes.get(); }
    public String getTimestamp() { return timestamp.get(); }
    public String getDuration() { return duration.get(); }
    public String getNote() { return note.get(); }
    public int getDeviceId() { return deviceId; }

    public void setId(int id) { this.id.set(id); }
    public void setNote(String note) { this.note.set(note); }
    public void setDeviceId(int deviceId) { this.deviceId = deviceId; }

    public SimpleStringProperty noteProperty() { return note; }

    /** Human readable size, used by the "Size" table column. */
    public String getSizeDisplay() {
        long s = sizeBytes.get();
        if (s < 1024) return s + " B";
        if (s < 1024 * 1024) return String.format("%.1f KB", s / 1024.0);
        return String.format("%.1f MB", s / (1024.0 * 1024));
    }
}
