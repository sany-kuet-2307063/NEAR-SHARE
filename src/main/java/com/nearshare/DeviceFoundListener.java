package com.nearshare;

/**
 * Callback interface implemented by whoever wants to be told about devices
 * as the LAN scanner finds them. Keeping this as an interface means
 * LanScannerService never needs to know that MainController (or anything
 * JavaFX-related) exists — it just reports to whatever listener it was given.
 */
public interface DeviceFoundListener {
    void onDeviceFound(Device device);
    void onScanFinished(int devicesFound);
}
