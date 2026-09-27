package com.nearshare;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches this machine's PUBLIC-facing network information (the address
 * your router shows to the internet, plus ISP/location) from a free JSON
 * API and parses the response. This satisfies "Networking & Data Parsing:
 * HTTP requests to fetch JSON data from the internet + parse it" -
 * separate from the LAN file-transfer sockets, which talk raw bytes, not
 * HTTP/JSON.
 */
public class PublicNetworkInfoService {

    private static final String ENDPOINT = "https://ipapi.co/json/";

    /** Simple immutable holder for the fields we care about out of the JSON response. */
    public record PublicNetworkInfo(String ip, String city, String region, String country, String isp) {}

    /** Performs a blocking HTTP GET - call this from a background thread, never the JavaFX thread. */
    public PublicNetworkInfo fetch() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Request failed with status " + response.statusCode());
        }

        // ---- Parse the JSON body ----
        JSONObject json = new JSONObject(response.body());
        String ip = json.optString("ip", "unknown");
        String city = json.optString("city", "unknown");
        String region = json.optString("region", "unknown");
        String country = json.optString("country_name", "unknown");
        String isp = json.optString("org", "unknown");

        return new PublicNetworkInfo(ip, city, region, country, isp);
    }
}
