package com.nearshare;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


public class PublicNetworkInfoService {

    private static final String ENDPOINT = "https://ipapi.co/json/";
    public record PublicNetworkInfo(String ip, String city, String region, String country, String isp) {}

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
//here the parsing
        JSONObject json = new JSONObject(response.body());
        String ip = json.optString("ip", "unknown");
        String city = json.optString("city", "unknown");
        String region = json.optString("region", "unknown");
        String country = json.optString("country_name", "unknown");
        String isp = json.optString("org", "unknown");

        return new PublicNetworkInfo(ip, city, region, country, isp);
    }
}
