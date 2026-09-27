package com.nearshare;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence layer backed by a local SQLite database file
 * (nearshare_history.db). It implements Repository&lt;HistoryEntry&gt; for
 * full CRUD on transfer history, and additionally manages two related
 * tables:
 *
 *   devices  (id PK, name, ip UNIQUE, last_seen, favorite)
 *   history  (id PK, file_name, direction, peer_ip, device_id FK -> devices.id,
 *             size_bytes, timestamp, duration, note)
 *   settings (key PK, value)   -- key/value store, used for the transfer PIN
 *
 * Every completed send/receive is written to "history" and is linked to a
 * row in "devices" via the device_id foreign key: recordHistory() looks up
 * (or creates) a device for the peer's IP address before inserting, which
 * is how the relationship between the two tables gets established.
 */
public class DatabaseManager implements Repository<HistoryEntry> {

    private static final String DB_URL = "jdbc:sqlite:nearshare_history.db";

    public DatabaseManager() {
        initDatabase();
    }

    private void initDatabase() {
        String devices = "CREATE TABLE IF NOT EXISTS devices (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT," +
                "ip TEXT UNIQUE NOT NULL," +
                "last_seen TEXT," +
                "favorite INTEGER DEFAULT 0" +
                ")";

        String history = "CREATE TABLE IF NOT EXISTS history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_name TEXT NOT NULL," +
                "direction TEXT NOT NULL," +
                "peer_ip TEXT," +
                "device_id INTEGER," +
                "size_bytes INTEGER," +
                "timestamp TEXT," +
                "duration TEXT," +
                "note TEXT," +
                "FOREIGN KEY(device_id) REFERENCES devices(id)" +
                ")";

        String settings = "CREATE TABLE IF NOT EXISTS settings (" +
                "key TEXT PRIMARY KEY," +
                "value TEXT" +
                ")";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute(devices);
            stmt.execute(history);
            stmt.execute(settings);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------
    // Repository<HistoryEntry> implementation (Create / Read / Update / Delete)
    // ---------------------------------------------------------------

    /** CREATE (low-level insert; prefer recordHistory() which also links a device). */
    @Override
    public void create(HistoryEntry entry) {
        String sql = "INSERT INTO history (file_name, direction, peer_ip, device_id, size_bytes, timestamp, duration, note) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.getFileName());
            ps.setString(2, entry.getDirection());
            ps.setString(3, entry.getPeerIp());
            if (entry.getDeviceId() > 0) ps.setInt(4, entry.getDeviceId()); else ps.setNull(4, java.sql.Types.INTEGER);
            ps.setLong(5, entry.getSizeBytes());
            ps.setString(6, entry.getTimestamp());
            ps.setString(7, entry.getDuration());
            ps.setString(8, entry.getNote());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) entry.setId(keys.getInt(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** READ all history, newest first. */
    @Override
    public List<HistoryEntry> readAll() {
        List<HistoryEntry> list = new ArrayList<>();
        String sql = "SELECT id, file_name, direction, peer_ip, device_id, size_bytes, timestamp, duration, note " +
                "FROM history ORDER BY id DESC";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new HistoryEntry(
                        rs.getInt("id"),
                        rs.getString("file_name"),
                        rs.getString("direction"),
                        rs.getString("peer_ip"),
                        rs.getLong("size_bytes"),
                        rs.getString("timestamp"),
                        rs.getString("duration"),
                        rs.getString("note"),
                        rs.getInt("device_id")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /** UPDATE: currently used to let the user attach/edit a note on a past transfer. */
    @Override
    public void update(HistoryEntry entry) {
        String sql = "UPDATE history SET note = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.getNote());
            ps.setInt(2, entry.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** DELETE a single history row by id. */
    @Override
    public void delete(int id) {
        String sql = "DELETE FROM history WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** Bulk delete, used by "Clear History". */
    public void clearHistory() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM history");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Kept for backwards compatibility with earlier call sites / naming.
    public void insertHistory(HistoryEntry entry) { create(entry); }
    public List<HistoryEntry> getAllHistory() { return readAll(); }

    /**
     * Records a completed transfer AND establishes the relationship to the
     * devices table: finds the device row for this peer IP (creating one if
     * it's never been seen before), stamps its last_seen time, and stores
     * that device's id as the foreign key on the new history row.
     */
    public synchronized void recordHistory(HistoryEntry entry, String timestampNow) {
        int deviceId = upsertDevice(entry.getPeerIp(), null, timestampNow);
        entry.setDeviceId(deviceId);
        create(entry);
    }

    // ---------------------------------------------------------------
    // devices table
    // ---------------------------------------------------------------

    /** Insert a new device or update its last_seen (and name, if provided) if it already exists. Returns its id. */
    public synchronized int upsertDevice(String ip, String name, String lastSeen) {
        if (ip == null || ip.isBlank()) return -1;
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (PreparedStatement find = conn.prepareStatement("SELECT id FROM devices WHERE ip = ?")) {
                find.setString(1, ip);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        try (PreparedStatement upd = conn.prepareStatement(
                                name != null ? "UPDATE devices SET last_seen = ?, name = ? WHERE id = ?"
                                             : "UPDATE devices SET last_seen = ? WHERE id = ?")) {
                            upd.setString(1, lastSeen);
                            if (name != null) {
                                upd.setString(2, name);
                                upd.setInt(3, id);
                            } else {
                                upd.setInt(2, id);
                            }
                            upd.executeUpdate();
                        }
                        return id;
                    }
                }
            }
            String deviceName = (name != null) ? name : ("Device @ " + ip);
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO devices (name, ip, last_seen, favorite) VALUES (?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, deviceName);
                ins.setString(2, ip);
                ins.setString(3, lastSeen);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public ObservableList<Device> getAllDevices() {
        ObservableList<Device> list = FXCollections.observableArrayList();
        String sql = "SELECT id, name, ip, last_seen FROM devices ORDER BY last_seen DESC";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Device(rs.getInt("id"), rs.getString("name"), rs.getString("ip"),
                        rs.getString("last_seen"), "Known"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * Demonstrates a JOIN across the two related tables: history rows with
     * the human-friendly device name attached, instead of just a raw IP.
     */
    public List<String> getHistoryWithDeviceNames() {
        List<String> rows = new ArrayList<>();
        String sql = "SELECT h.file_name, h.direction, d.name AS device_name, h.timestamp " +
                "FROM history h LEFT JOIN devices d ON h.device_id = d.id " +
                "ORDER BY h.id DESC";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString("file_name") + " | " + rs.getString("direction") + " | " +
                        rs.getString("device_name") + " | " + rs.getString("timestamp"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    // ---------------------------------------------------------------
    // settings table (used for the optional incoming-transfer PIN)
    // ---------------------------------------------------------------

    public void setSetting(String key, String value) {
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getSetting(String key) {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /** One-way hash so the PIN is never stored (or sent over the socket) in plain text. */
    public static String hashPin(String pin) {
        if (pin == null) pin = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(pin.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return pin; // SHA-256 is always available on the JVM; this is just a safe fallback
        }
    }
}
