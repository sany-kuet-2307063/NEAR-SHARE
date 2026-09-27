package com.nearshare;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Helper for automatically discovering this machine's local network IP
 * address (so the user never has to type it in manually), and for working
 * out the /24 subnet prefix so the LAN scanner knows which addresses to
 * probe.
 */
public class NetworkUtils {

    /** Automatically detects this device's own LAN IP (e.g. over Wi-Fi). */
    public static String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
                    if (addr.isLoopbackAddress()) continue;
                    if (addr.getHostAddress().contains(":")) continue; // skip IPv6
                    return addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    /** Given "192.168.1.42" returns "192.168.1." so the scanner can try .1 through .254. */
    public static String getSubnetPrefix(String localIp) {
        int lastDot = localIp.lastIndexOf('.');
        if (lastDot == -1) return null;
        return localIp.substring(0, lastDot + 1);
    }
}
