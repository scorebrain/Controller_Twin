/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.emech.mp1802emulator;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Instantiate the master controller manually
        ConsoleController controller = new ConsoleController();
        
        // Let the controller build the UI based on the Manifest.ini
        Scene scene = new Scene(controller.buildMasterShell());
        
        stage.setTitle("Electro-Mech MP/MM Scoreboard Control Console Emulator");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            javafx.application.Platform.exit();
            System.exit(0);
        });
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}