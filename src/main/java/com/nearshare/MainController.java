package com.nearshare;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

    // ---- Header ----
    @FXML private Label localIpLabel;
    @FXML private Button copyIpBtn;
    @FXML private Label statusLabel;
    @FXML private Label statusDot;
    @FXML private Label scanTimeLabel;

    // ---- Send panel ----
    @FXML private StackPane dropZone;
    @FXML private HBox selectedFileBox;
    @FXML private Label selectedFileNameLabel;
    @FXML private Label selectedFileSizeLabel;
    @FXML private TextField targetIpField;
    @FXML private PasswordField sendPinField;
    @FXML private Button sendButton;
    @FXML private ProgressBar transferProgressBar;

    // ---- Security settings ----
    @FXML private PasswordField myPinField;
    @FXML private CheckBox requirePinCheck;

    // ---- History tab ----
    @FXML private TextField searchField;
    @FXML private Label totalSentLabel;
    @FXML private Label totalReceivedLabel;
    @FXML private Label historyCountLabel;
    @FXML private TableView<HistoryEntry> historyTable;
    @FXML private TableColumn<HistoryEntry, String> colFile;
    @FXML private TableColumn<HistoryEntry, String> colDirection;
    @FXML private TableColumn<HistoryEntry, String> colPeer;
    @FXML private TableColumn<HistoryEntry, String> colSize;
    @FXML private TableColumn<HistoryEntry, String> colTime;
    @FXML private TableColumn<HistoryEntry, String> colDuration;
    @FXML private TableColumn<HistoryEntry, String> colNote;

    // ---- Devices & Network tab ----
    @FXML private Button scanButton;
    @FXML private Label scanStatusLabel;
    @FXML private TableView<Device> devicesTable;
    @FXML private TableColumn<Device, String> colDeviceName;
    @FXML private TableColumn<Device, String> colDeviceIp;
    @FXML private TableColumn<Device, String> colDeviceLastSeen;
    @FXML private TableColumn<Device, String> colDeviceStatus;
    @FXML private Button checkPublicInfoButton;
    @FXML private Label publicIpLabel;
    @FXML private Label publicLocationLabel;
    @FXML private Label publicIspLabel;

    private static final int PORT = 8080;

    private DatabaseManager db;
    private LanScannerService lanScanner;
    private PublicNetworkInfoService publicNetworkInfoService;

    /** Shared thread pool: handles every outgoing send AND every incoming connection concurrently. */
    private ExecutorService transferPool;

    private int scanSeconds = 0;
    private File selectedFile = null;
    private String myIp;

    private final ObservableList<HistoryEntry> masterHistoryList = FXCollections.observableArrayList();
    private final ObservableList<Device> devicesList = FXCollections.observableArrayList();
    private FilteredList<HistoryEntry> filteredHistoryList;

    private int sentCount = 0;
    private int receivedCount = 0;

    @FXML
    public void initialize() {
        db = new DatabaseManager();
        lanScanner = new LanScannerService(db);
        publicNetworkInfoService = new PublicNetworkInfoService();

        // A cached thread pool grows/shrinks with demand - perfect for bursts of
        // simultaneous sends/receives without the overhead of an unlimited number
        // of raw threads.
        transferPool = Executors.newCachedThreadPool();

        // Auto-detect this device's own LAN IP address (over Wi-Fi/Ethernet) - no manual typing required.
        myIp = NetworkUtils.getLocalIPAddress();
        localIpLabel.setText(myIp);

        setupHistoryTable();
        setupDevicesTable();
        loadPersistedSettings();
        loadHistoryFromDatabase();
        setupDragAndDrop();

        // Background server: accepts connections and dispatches each to the thread pool.
        Thread receiverThread = new Thread(new ReceiverServerTask(this, db, transferPool, PORT));
        receiverThread.setDaemon(true);
        receiverThread.start();

        // Uptime clock.
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickScanTime()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    // ---------------------------------------------------------------
    // Setup helpers
    // ---------------------------------------------------------------

    private void setupHistoryTable() {
        colFile.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colDirection.setCellValueFactory(new PropertyValueFactory<>("direction"));
        colPeer.setCellValueFactory(new PropertyValueFactory<>("peerIp"));
        colSize.setCellValueFactory(new PropertyValueFactory<>("sizeDisplay"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("timestamp"));
        colDuration.setCellValueFactory(new PropertyValueFactory<>("duration"));

        colDirection.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String direction, boolean empty) {
                super.updateItem(direction, empty);
                if (empty || direction == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label();
                    if ("Received".equalsIgnoreCase(direction)) {
                        badge.getStyleClass().add("badge-received");
                        badge.setText("\u2B07 " + direction.toUpperCase());
                    } else {
                        badge.getStyleClass().add("badge-sent");
                        badge.setText("\u2B06 " + direction.toUpperCase());
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        colPeer.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String ip, boolean empty) {
                super.updateItem(ip, empty);
                if (empty || ip == null) {
                    setText(null);
                } else {
                    setText(ip);
                    getStyleClass().add("ip-tag");
                }
            }
        });

        // Editable "Note" column - this is the Update half of CRUD: double-click a
        // cell, type something, press Enter, and it's written back to the database.
        historyTable.setEditable(true);
        colNote.setCellValueFactory(new PropertyValueFactory<>("note"));
        colNote.setCellFactory(TextFieldTableCell.forTableColumn());
        colNote.setOnEditCommit(event -> {
            HistoryEntry entry = event.getRowValue();
            entry.setNote(event.getNewValue());
            db.update(entry); // Repository<HistoryEntry>.update(...)
        });

        filteredHistoryList = new FilteredList<>(masterHistoryList, p -> true);
        searchField.textProperty().addListener((obs, oldV, newV) -> {
            filteredHistoryList.setPredicate(entry -> {
                if (newV == null || newV.isBlank()) return true;
                String f = newV.toLowerCase().trim();
                return (entry.getFileName() != null && entry.getFileName().toLowerCase().contains(f))
                        || (entry.getPeerIp() != null && entry.getPeerIp().toLowerCase().contains(f))
                        || (entry.getDirection() != null && entry.getDirection().toLowerCase().contains(f))
                        || (entry.getNote() != null && entry.getNote().toLowerCase().contains(f));
            });
            updateHistoryCountUI();
        });

        SortedList<HistoryEntry> sortedList = new SortedList<>(filteredHistoryList);
        sortedList.comparatorProperty().bind(historyTable.comparatorProperty());
        historyTable.setItems(sortedList);
    }

    private void setupDevicesTable() {
        colDeviceName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colDeviceIp.setCellValueFactory(new PropertyValueFactory<>("ip"));
        colDeviceLastSeen.setCellValueFactory(new PropertyValueFactory<>("lastSeen"));
        colDeviceStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        devicesTable.setItems(devicesList);
        devicesList.addAll(db.getAllDevices());

        // Double-click a device row to auto-fill the target IP field - small UX touch.
        devicesTable.setRowFactory(tv -> {
            TableRow<Device> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    targetIpField.setText(row.getItem().getIp());
                }
            });
            return row;
        });
    }

    private void loadPersistedSettings() {
        String storedHash = db.getSetting("pin_hash");
        boolean required = "true".equals(db.getSetting("pin_required"));
        requirePinCheck.setSelected(required && storedHash != null && !storedHash.isBlank());
    }

    private void loadHistoryFromDatabase() {
        masterHistoryList.addAll(db.readAll());
        for (HistoryEntry entry : masterHistoryList) {
            if ("Sent".equalsIgnoreCase(entry.getDirection())) sentCount++;
            else receivedCount++;
        }
        updateStatsUI();
        updateHistoryCountUI();
    }

    private void setupDragAndDrop() {
        dropZone.setOnDragOver(event -> {
            if (event.getGestureSource() != dropZone && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        dropZone.setOnDragEntered(event -> {
            if (event.getGestureSource() != dropZone && event.getDragboard().hasFiles()) {
                dropZone.getStyleClass().add("drop-zone-active");
            }
            event.consume();
        });

        dropZone.setOnDragExited(event -> {
            dropZone.getStyleClass().remove("drop-zone-active");
            event.consume();
        });

        dropZone.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            if (dragboard.hasFiles() && !dragboard.getFiles().isEmpty()) {
                setSelectedFile(dragboard.getFiles().get(0));
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void tickScanTime() {
        scanSeconds++;
        int h = scanSeconds / 3600;
        int m = (scanSeconds % 3600) / 60;
        int s = scanSeconds % 60;
        scanTimeLabel.setText(String.format("Uptime: %02d:%02d:%02d", h, m, s));
    }

    // ---------------------------------------------------------------
    // Button actions
    // ---------------------------------------------------------------

    @FXML
    public void onCopyIpClicked() {
        String ip = localIpLabel.getText();
        if (ip != null && !ip.isBlank()) {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(ip);
            clipboard.setContent(content);

            copyIpBtn.setText("\u2713 Copied!");
            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(e -> copyIpBtn.setText("\uD83D\uDCCB Copy"));
            pause.play();
        }
    }

    @FXML
    public void onBrowseFileClicked() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select a file to send");
        File chosen = fileChooser.showOpenDialog(targetIpField.getScene().getWindow());
        if (chosen != null) setSelectedFile(chosen);
    }

    public void setSelectedFile(File file) {
        this.selectedFile = file;
        if (file != null) {
            selectedFileNameLabel.setText(file.getName());
            selectedFileSizeLabel.setText(formatSize(file.length()));
            selectedFileBox.setVisible(true);
            selectedFileBox.setManaged(true);
        } else {
            selectedFileBox.setVisible(false);
            selectedFileBox.setManaged(false);
        }
    }

    @FXML
    public void onClearSelectedFile() {
        setSelectedFile(null);
    }

    @FXML
    public void onSendButtonClicked() {
        String targetIp = targetIpField.getText();
        if (targetIp == null || targetIp.isBlank()) {
            setStatus("Please enter receiver's IP address.", StatusType.ERROR);
            return;
        }

        if (selectedFile == null) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select a file to send");
            File chosen = fileChooser.showOpenDialog(targetIpField.getScene().getWindow());
            if (chosen == null) {
                setStatus("Transfer cancelled.", StatusType.INFO);
                return;
            }
            setSelectedFile(chosen);
        }

        File fileToSend = selectedFile;
        String pin = sendPinField.getText();
        // Submitted to the shared thread pool instead of a raw `new Thread(...).start()`.
        transferPool.submit(new SenderTask(targetIp.trim(), fileToSend, this, db, PORT, pin));
    }

    /** Saves (or clears) the PIN that incoming transfers must present. */
    @FXML
    public void onSavePinClicked() {
        boolean required = requirePinCheck.isSelected();
        String pin = myPinField.getText();

        if (required && (pin == null || pin.isBlank())) {
            setStatus("Enter a PIN before requiring one for incoming transfers.", StatusType.ERROR);
            return;
        }

        db.setSetting("pin_required", String.valueOf(required));
        db.setSetting("pin_hash", required ? DatabaseManager.hashPin(pin) : "");
        setStatus(required ? "Incoming transfers now require your PIN." : "PIN requirement disabled.", StatusType.ACTIVE);
        myPinField.clear();
    }

    @FXML
    public void onScanNetworkClicked() {
        scanButton.setDisable(true);
        scanStatusLabel.setText("Scanning " + NetworkUtils.getSubnetPrefix(myIp) + "0/24 ...");
        devicesList.clear();

        lanScanner.scanAsync(myIp, PORT, new DeviceFoundListener() {
            @Override
            public void onDeviceFound(Device device) {
                devicesList.add(device);
            }

            @Override
            public void onScanFinished(int devicesFound) {
                scanButton.setDisable(false);
                scanStatusLabel.setText(devicesFound + " device(s) found with NearShare open.");
            }
        });
    }

    /** Networking & Data Parsing feature: fetch + parse JSON from a public HTTP API. */
    @FXML
    public void onCheckPublicInfoClicked() {
        checkPublicInfoButton.setDisable(true);
        publicIpLabel.setText("Checking...");
        publicLocationLabel.setText("");
        publicIspLabel.setText("");

        transferPool.submit(() -> {
            try {
                PublicNetworkInfoService.PublicNetworkInfo info = publicNetworkInfoService.fetch();
                Platform.runLater(() -> {
                    publicIpLabel.setText("Public IP: " + info.ip());
                    publicLocationLabel.setText("Location: " + info.city() + ", " + info.region() + ", " + info.country());
                    publicIspLabel.setText("ISP: " + info.isp());
                    checkPublicInfoButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    publicIpLabel.setText("Couldn't reach the internet info service.");
                    checkPublicInfoButton.setDisable(false);
                });
            }
        });
    }

    /** Delete: removes a single selected history row (the other half of CRUD, alongside "Clear all"). */
    @FXML
    public void onDeleteSelectedHistory() {
        HistoryEntry selected = historyTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a history row to delete first.", StatusType.INFO);
            return;
        }
        db.delete(selected.getId());
        masterHistoryList.remove(selected);
        if ("Sent".equalsIgnoreCase(selected.getDirection())) sentCount--; else receivedCount--;
        updateStatsUI();
        updateHistoryCountUI();
    }

    @FXML
    public void onClearHistoryClicked() {
        if (masterHistoryList.isEmpty()) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Transfer History");
        alert.setHeaderText("Clear all transfer records?");
        alert.setContentText("This will permanently clear your transfer history logs.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            db.clearHistory();
            masterHistoryList.clear();
            sentCount = 0;
            receivedCount = 0;
            updateStatsUI();
            updateHistoryCountUI();
            setStatus("Transfer history cleared.", StatusType.INFO);
        }
    }

    @FXML
    public void onOpenDownloadsClicked() {
        try {
            File downloadDir = new File(System.getProperty("user.home"), "NearShare_Downloads");
            if (!downloadDir.exists()) downloadDir.mkdirs();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(downloadDir);
            }
        } catch (Exception e) {
            setStatus("Failed to open downloads folder: " + e.getMessage(), StatusType.ERROR);
        }
    }

    // ---------------------------------------------------------------
    // Called by the transfer tasks (SenderTask / ReceiveConnectionTask)
    // ---------------------------------------------------------------

    public enum StatusType { ACTIVE, BUSY, ERROR, INFO }

    public void setStatus(String text) {
        setStatus(text, StatusType.ACTIVE);
    }

    public void setStatus(String text, StatusType type) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusDot.getStyleClass().removeAll("status-dot-active", "status-dot-busy", "status-dot-error");
            switch (type) {
                case BUSY -> statusDot.getStyleClass().add("status-dot-busy");
                case ERROR -> statusDot.getStyleClass().add("status-dot-error");
                default -> statusDot.getStyleClass().add("status-dot-active");
            }
        });
    }

    public void setTransferActive(boolean active) {
        Platform.runLater(() -> {
            transferProgressBar.setVisible(active);
            transferProgressBar.setManaged(active);
            if (active) transferProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        });
    }

    /** Create: inserts the finished transfer and links it to a device row (foreign key). */
    public void recordHistory(HistoryEntry entry) {
        db.recordHistory(entry, entry.getTimestamp());
        Platform.runLater(() -> {
            masterHistoryList.add(0, entry);
            if ("Sent".equalsIgnoreCase(entry.getDirection())) sentCount++; else receivedCount++;
            updateStatsUI();
            updateHistoryCountUI();
            refreshDevicesFromDb();
        });
    }

    private void refreshDevicesFromDb() {
        devicesList.setAll(db.getAllDevices());
    }

    private void updateStatsUI() {
        totalSentLabel.setText(String.valueOf(sentCount));
        totalReceivedLabel.setText(String.valueOf(receivedCount));
    }

    private void updateHistoryCountUI() {
        int count = filteredHistoryList != null ? filteredHistoryList.size() : masterHistoryList.size();
        historyCountLabel.setText(count + " transfer" + (count == 1 ? "" : "s"));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
