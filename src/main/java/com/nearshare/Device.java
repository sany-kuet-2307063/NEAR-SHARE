package com.nearshare;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;

/**
 * Represents a single known device on the LAN (a row in the "devices"
 * table). Every HistoryEntry that goes through this device is linked back
 * here via a foreign key (history.device_id -> devices.id), so we can
 * always answer "which device sent/received this file?" with a JOIN.
 */
public class Device {

    private final SimpleIntegerProperty id;
    private final SimpleStringProperty name;
    private final SimpleStringProperty ip;
    private final SimpleStringProperty lastSeen;
    private final SimpleStringProperty status;

    public Device(int id, String name, String ip, String lastSeen, String status) {
        this.id = new SimpleIntegerProperty(id);
        this.name = new SimpleStringProperty(name);
        this.ip = new SimpleStringProperty(ip);
        this.lastSeen = new SimpleStringProperty(lastSeen);
        this.status = new SimpleStringProperty(status);
    }

    public int getId() { return id.get(); }
    public String getName() { return name.get(); }
    public String getIp() { return ip.get(); }
    public String getLastSeen() { return lastSeen.get(); }
    public String getStatus() { return status.get(); }

    public void setName(String name) { this.name.set(name); }
    public void setStatus(String status) { this.status.set(status); }
}
