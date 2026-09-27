package com.nearshare;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class NearShareApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(NearShareApp.class.getResource("Dashboard.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1180, 720);
        scene.getStylesheets().add(
                Objects.requireNonNull(NearShareApp.class.getResource("style.css")).toExternalForm());

        stage.setTitle("NearShare \u2014 Fast P2P LAN File Transfer");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.show();
    }
}
