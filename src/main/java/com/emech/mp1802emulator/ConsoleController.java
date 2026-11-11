/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.emech.mp1802emulator;

/**
 *
 * @author CMcMichael
 */

import java.util.HashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.FileReader;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import com.fazecast.jSerialComm.SerialPort;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.CheckBox;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

public class ConsoleController {
    
    // --- THREAD SYNCHRONIZATION ---
    private volatile boolean isCpuPaused = true;
    
    private Cosmac1802 cpu;
    private HashMap<String, String> gbwDictionary = new HashMap<>();
    private SerialPort comPort; // Add the hardware port variable
    private boolean enableHardwareCom = false; // Set to true when USB adapter is attached
    private DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private StringBuilder logBuffer = new StringBuilder();
    
    // --- PROFILE & WINDOW MANAGEMENT ---
    private List<EmulatorProfile> availableProfiles = new ArrayList<>();
    private EmulatorProfile activeProfile;
    
    private HBox masterLayout;
    private VBox configDrawer;
    private javafx.scene.control.ComboBox<EmulatorProfile> profileCombo;
    private javafx.scene.control.CheckBox coldBootCheck;
    private javafx.scene.control.CheckBox[] jumperChecks = new javafx.scene.control.CheckBox[8];
    
    private Stage gcStage;
    private Stage pcStage;
    
    // --- SERIAL LOGGER & FILTER TRACKING ---
    public boolean showChangesOnly = true; // Configurable default state
    
    // 32 addresses. Initialize to -1 so the first 0x00 payload always registers as a change.
    private int[] addressState = new int[32]; 
    private boolean[] bankActive = new boolean[8]; // Tracks the Word 4 'i' bit for each of the 8 possible banks
    
    // Custom object to hold the formatted string and its color state
    private class LogEntry {
        String text;
        boolean isBankActive;
        LogEntry(String text, boolean isBankActive) { this.text = text; this.isBankActive = isBankActive; }
    }
    
    private ListView<LogEntry> serialLog;
    private ObservableList<LogEntry> logLinesList = FXCollections.observableArrayList();
    
    private Button pauseBtn;
    private Button copyBtn;
    private CheckBox filterCheckBox;
    private boolean isLogPaused = false;
    // ---------------------------------------
    
    // Handheld LED References
    public Circle gameClockLed;
    public Circle shotClockLed;
    
    // Handheld FXML Injections
    @FXML public Circle runningLight; // For both F16 and B23
    @FXML private Button btnRun;      // For 2000s Game Clock
    @FXML private Button btnStop;     // For 2000s Game Clock

    @FXML
    private Label line1LCD;

    @FXML
    private Label line2LCD;
    /*
    @FXML
    private javafx.scene.control.CheckBox jumperCheckBox;
*/
    public javafx.scene.layout.Pane buildMasterShell() {
        cpu = new Cosmac1802();
        cpu.controller = this;
        cpu.reset();
        
        // --- INITIALIZE PHYSICAL RS-232 HARDWARE ---
        if (enableHardwareCom) {
            comPort = SerialPort.getCommPort("COM3");
            // MP/Freymuth Spec: 2400 baud, 8 data bits, 1 stop bit, no parity
            comPort.setComPortParameters(2400, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            
            if (comPort.openPort()) {
                System.out.println("PHYSICAL HARDWARE: Successfully locked onto COM3.");
            } else {
                System.err.println("PHYSICAL HARDWARE: Failed to open COM3. Ensure no other apps are using it.");
            }

            // Add a shutdown hook to release the port when the app closes
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (comPort != null && comPort.isOpen()) {
                    comPort.closePort();
                    System.out.println("COM3 port released.");
                }
                if (cpu != null && activeProfile != null) {
                    ensureNvramDir();
                    cpu.saveNVRAM("nvram/" + activeProfile.protocolProfile + ".nvram");
                    System.out.println("Hardware NVRAM state preserved.");
                }
            }));
        } else {
            System.out.println("PHYSICAL HARDWARE: RS-232 output is disabled.");
        }
        // -------------------------------------------
        
        Arrays.fill(addressState, -1);
        
        // --- SPAWN INDEPENDENT SERIAL LOGGER WINDOW ---
        Label logHeader = new Label(" Time        | Raw   | Adr |   Payload    | Context\n Stamp       | Bytes | GBW | i | h ... a  | Description");
        logHeader.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #FFFFFF; -fx-background-color: #333333; -fx-padding: 5 5 5 8;");
        logHeader.setMaxWidth(Double.MAX_VALUE); 
        
        serialLog = new ListView<>(logLinesList);
        serialLog.setStyle("-fx-control-inner-background: #000000; -fx-background-color: #000000;");
        // Custom Cell Factory to colorize lines based on the Bank's Active state
        serialLog.setCellFactory(lv -> new ListCell<LogEntry>() {
            @Override
            protected void updateItem(LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: #000000;");
                } else {
                    setText(item.text);
                    setFont(javafx.scene.text.Font.font("Consolas", javafx.scene.text.FontWeight.BOLD, 12));
                    setTextFill(item.isBankActive ? Color.LIMEGREEN : Color.GRAY);
                    setStyle("-fx-background-color: #000000;"); // Hides the blue selection highlight
                }
            }
        });
        
        filterCheckBox = new CheckBox("Changes Only");
        filterCheckBox.setSelected(showChangesOnly);
        filterCheckBox.setOnAction(e -> showChangesOnly = filterCheckBox.isSelected());
        
        pauseBtn = new Button("Pause Log");
        copyBtn = new Button("Copy Log");
        
        pauseBtn.setOnAction(e -> {
            isLogPaused = !isLogPaused;
            pauseBtn.setText(isLogPaused ? "Resume Log" : "Pause Log");
        });
        
        copyBtn.setOnAction(e -> {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            StringBuilder sb = new StringBuilder();
            sb.append(logHeader.getText()).append("\n");
            for (LogEntry entry : logLinesList) sb.append(entry.text).append("\n");
            content.putString(sb.toString());
            clipboard.setContent(content);
        });

        HBox buttonBar = new HBox(15, filterCheckBox, pauseBtn, copyBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(5));
        buttonBar.setStyle("-fx-background-color: #d9d9d9;");

        VBox logRoot = new VBox(logHeader, serialLog, buttonBar);
        VBox.setVgrow(serialLog, Priority.ALWAYS);

        Stage logStage = new Stage();
        logStage.setTitle("Controller Output Serial Data Trace");
        logStage.setScene(new Scene(logRoot, 650, 300));
        logStage.setX(100); 
        logStage.setY(100);
        logStage.show();
        // ----------------------------------------------
        spawnHandheldWindows();
        
        loadManifest("Manifest.ini");
        buildConfigDrawer();

        masterLayout = new HBox();
        masterLayout.setStyle("-fx-background-color: #2b2b2b;");

        // Boot the first profile found in the manifest
        if (!availableProfiles.isEmpty()) {
            activeProfile = availableProfiles.get(0);
            profileCombo.getSelectionModel().select(activeProfile);
            applyProfile(activeProfile);
        }

        // Start the CPU in a background daemon thread
        Thread cpuThread = new Thread(() -> {
            long startTime = System.nanoTime();
            final double NS_PER_CYCLE = 4469.84;
            long lastThrottleCheck = 0;
            
            try {
                while (true) {
                    if (isCpuPaused) {
                        Thread.sleep(10); // Hold CPU in RESET state
                        // Reset the throttle clock so we don't hyper-speed when unpaused
                        startTime = System.nanoTime(); 
                        lastThrottleCheck = cpu.totalCycles;
                        continue;
                    }
                    
                    cpu.step();
                    
                    // EF2 Hardware Oscillator: Toggle state every 2048 machine cycles
                    cpu.ef2State = ((cpu.totalCycles / 2048) % 2 == 0) ? 1 : 0;
                    
                    // Self-correcting real-time throttle
                    if (cpu.totalCycles - lastThrottleCheck >= 10000) {
                        lastThrottleCheck = cpu.totalCycles;
                        long expectedElapsedNs = (long) (cpu.totalCycles * NS_PER_CYCLE);
                        long actualElapsedNs = System.nanoTime() - startTime;
                        long differenceNs = expectedElapsedNs - actualElapsedNs;
                        
                        if (differenceNs > 2000000) {
                            Thread.sleep(differenceNs / 1000000);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        cpuThread.setDaemon(true); 
        cpuThread.start();
        
        return masterLayout;
    }
    
    // --- MANIFEST PARSER ---
    private void loadManifest(String filename) {
        availableProfiles.clear();
        EmulatorProfile currentProfile = null;

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // New Section [PROFILE_ID]
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (currentProfile != null) availableProfiles.add(currentProfile);
                    currentProfile = new EmulatorProfile();
                    currentProfile.sectionId = line.substring(1, line.length() - 1);
                    continue;
                }

                // Key=Value parsing
                if (currentProfile != null && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim();

                    // Strip leading and trailing quotes if present
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }

                    switch (key) {
                        case "Name": currentProfile.name = val; break;
                        case "HardwarePlatform": currentProfile.hardwarePlatform = val; break;
                        case "HexFile": currentProfile.hexFile = val; break;
                        case "ReleaseDate": currentProfile.releaseDate = val; break;
                        case "RetireData": currentProfile.retireDate = val; break;
                        case "SportType": currentProfile.sportType = val; break;
                        case "KeypadLayout": currentProfile.keypadLayout = val; break;
                        case "HandheldMainTimer": currentProfile.handheldMainTimer = val; break;
                        case "HandheldAux": currentProfile.handheldAux = val; break;
                        case "ProtocolProfile": currentProfile.protocolProfile = val; break;
                        case "OptionJumpers":
                            if (val.startsWith("0b")) {
                                currentProfile.optionJumpers = Integer.parseInt(val.substring(2), 2);
                            } else {
                                currentProfile.optionJumpers = Integer.decode(val);
                            }
                            break;
                        case "ScoreboardModels":
                            // Parse arrays like ["MP-259", "MP-269"]
                            val = val.replace("[", "").replace("]", "");
                            String[] models = val.split(",");
                            for (String m : models) {
                                currentProfile.scoreboardModels.add(m.trim().replace("\"", ""));
                            }
                            break;
                    }
                }
            }
            // Add the final profile after the loop finishes
            if (currentProfile != null) availableProfiles.add(currentProfile);
            
            System.out.println("Manifest Loaded: Found " + availableProfiles.size() + " profiles.");
            for (EmulatorProfile p : availableProfiles) {
                System.out.println(" - " + p.sectionId + ": " + p.name + " (Jumpers: " + p.optionJumpers + ")");
            }

        } catch (Exception e) {
            System.err.println("Failed to load Manifest.ini: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void buildConfigDrawer() {
        configDrawer = new VBox(15);
        configDrawer.setPadding(new Insets(20));
        configDrawer.setStyle("-fx-background-color: #2b2b2b; -fx-font-family: 'Arial';");
        configDrawer.setPrefWidth(260);

        Label title = new Label("EMULATOR CONFIG");
        title.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        profileCombo = new javafx.scene.control.ComboBox<>();
        profileCombo.getItems().addAll(availableProfiles);
        profileCombo.setMaxWidth(Double.MAX_VALUE);
        
        // Auto-update checkboxes when a new profile is selected in the dropdown
        profileCombo.setOnAction(e -> {
            EmulatorProfile p = profileCombo.getValue();
            if (p != null) {
                for (int i = 0; i < 8; i++) {
                    // Check if the specific bit is a 1 in the TOML binary literal
                    jumperChecks[i].setSelected((p.optionJumpers & (1 << i)) != 0);
                }
            }
        });

        VBox jumperBox = new VBox(8);
        Label jumperLabel = new Label("J6 Option Jumpers (Pins 1-8):");
        jumperLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        jumperBox.getChildren().add(jumperLabel);
        
        for (int i = 0; i < 8; i++) {
            jumperChecks[i] = new javafx.scene.control.CheckBox("Jumper Pin " + (i + 1));
            jumperChecks[i].setStyle("-fx-text-fill: white;");
            jumperBox.getChildren().add(jumperChecks[i]);
        }
        
        coldBootCheck = new javafx.scene.control.CheckBox("Force Cold Boot (Wipe RAM)");
        coldBootCheck.setStyle("-fx-text-fill: #ffaa00; -fx-padding: 10 0 0 0;");

        Button btnApply = new Button("REBOOT HARDWARE");
        btnApply.setMaxWidth(Double.MAX_VALUE);
        btnApply.setStyle("-fx-background-color: #a32035; -fx-text-fill: white; -fx-font-weight: bold;");
        btnApply.setOnAction(e -> applyProfile(profileCombo.getValue()));

        configDrawer.getChildren().addAll(title, profileCombo, jumperBox, coldBootCheck, new Region(), btnApply);
        VBox.setVgrow(configDrawer.getChildren().get(4), Priority.ALWAYS); // Push button to bottom
    }
    
    private void applyProfile(EmulatorProfile p) {
        if (p == null) return;

        // 1. Halt the CPU thread immediately
        isCpuPaused = true; 

        // 2. Save NVRAM to the subfolder using the ProtocolProfile name
        if (activeProfile != null && cpu != null && activeProfile != p) {
            ensureNvramDir();
            cpu.saveNVRAM("nvram/" + activeProfile.protocolProfile + ".nvram");
        }
        activeProfile = p;

        // 3. Parse manual jumper overrides
        int customJumpers = 0;
        for (int i = 0; i < 8; i++) {
            if (jumperChecks[i].isSelected()) customJumpers |= (1 << i);
        }
        if (cpu != null) cpu.jumperState = ~customJumpers & 0xFF; 

        // --- GHOST UI FIX ---
        // Explicitly clear FXML bindings so detached UI elements don't survive the swap
        line1LCD = null;
        line2LCD = null;
        if (gameClockLed != null) gameClockLed = null;
        if (shotClockLed != null) shotClockLed = null;
        // --------------------

        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource(p.keypadLayout));
            loader.setController(this); // Safely injects the FXML elements into THIS active controller
            Pane newKeypad = loader.load();
            
            // Inject the invisible gear trigger button
            Button gearBtn = new Button("⚙");
            gearBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #555555; -fx-font-size: 20px; -fx-cursor: hand;");
            gearBtn.setLayoutX(5); gearBtn.setLayoutY(5);
            gearBtn.setOnAction(e -> toggleDrawer());
            newKeypad.getChildren().add(gearBtn);

            // Replace the old keypad with the new one
            masterLayout.getChildren().clear();
            masterLayout.getChildren().add(newKeypad);

            if (masterLayout.getScene() != null) {
                masterLayout.getScene().getWindow().sizeToScene(); // Snap OS window to new dimensions
            }

        } catch (Exception e) {
            System.err.println("Failed to load keypad layout: " + p.keypadLayout);
            e.printStackTrace();
        }

        loadProtocolDictionary("Protocol.ini", p.protocolProfile);
        
        if (cpu != null) {
            cpu.reset();
            java.util.Arrays.fill(cpu.memory, 0); 
            
            // Check if the user requested a destructive cold boot
            if (coldBootCheck != null && coldBootCheck.isSelected()) {
                System.out.println("Forcing Cold Boot (Bypassing NVRAM).");
                coldBootCheck.setSelected(false); // Reset the toggle for next time
            } else {
                cpu.loadNVRAM("nvram/" + p.protocolProfile + ".nvram");
            }
            
            try {
                IntelHexLoader.load(p.hexFile, cpu.memory);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        spawnHandheldWindows();
        
        // 4. Release the RESET line and let the OS boot!
        isCpuPaused = false;
    }
    
    private void toggleDrawer() {
        if (masterLayout.getChildren().contains(configDrawer)) {
            masterLayout.getChildren().remove(configDrawer);
        } else {
            masterLayout.getChildren().add(0, configDrawer); 
        }
        
        // Use masterLayout to resize, avoiding the NPE on MM boards
        if (masterLayout.getScene() != null) {
            masterLayout.getScene().getWindow().sizeToScene();
        }
    }
 
    // --- PROTOCOL DICTIONARY LOADER ---
    private void loadProtocolDictionary(String filename, String profile) {
        gbwDictionary.clear();
        boolean inProfile = false;
        
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue; // Skip blanks and comments
                
                if (line.startsWith("[")) {
                    inProfile = line.equalsIgnoreCase("[" + profile + "]");
                    continue;
                }
                
                if (inProfile && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    gbwDictionary.put(parts[0].trim(), parts[1].trim());
                }
            }
            System.out.println("Loaded " + gbwDictionary.size() + " GBW definitions for profile: " + profile);
        } catch (Exception e) {
            System.err.println("Failed to load Protocol.ini: " + e.getMessage());
        }
    }

    // Called by Cosmac1802.java to push text to the UI
    public void updateLCD(String line1, String line2) {
        Platform.runLater(() -> {
            if (line1LCD != null) line1LCD.setText(line1);
            if (line2LCD != null) line2LCD.setText(line2);
        });
    }
   
/*    @FXML
    public void handleJumperToggle(javafx.event.ActionEvent event) {
        if (cpu != null) {
            cpu.jumperState = jumperCheckBox.isSelected() ? 0x00 : 0xFF;
        }
    }  */

    // The Universal Button Press Handler
    // 8 columns (0 - 7) x 5 rows (0=Row B, 1=Row C, 2 = Row D, 3=Row E, 5=Row F)
    @FXML
    public void handleMatrixPress(MouseEvent event) {
        if (cpu != null) {
            javafx.scene.control.Button btn = (javafx.scene.control.Button) event.getSource();
            String data = (String) btn.getUserData();
            System.out.println(data);
            if (data != null && !data.equals("?,?")) {
                String[] coords = data.split(",");
                if (coords.length == 2) {
                    cpu.targetColumn = Integer.parseInt(coords[0]);
                    cpu.targetRow = Integer.parseInt(coords[1]);
                    System.out.printf("UI Thread: Button [%s] Pressed. Col:%d Row:%d\n", 
                            btn.getText().replace("\n", " "), cpu.targetColumn, cpu.targetRow);
                }
            } else {
                System.out.printf("UI Thread: Button [%s] Pressed, but coordinates are unmapped.\n", 
                        btn.getText().replace("\n", " "));
            }
        }
    }

    // The Universal Button Release Handler
    @FXML
    public void handleMatrixRelease(MouseEvent event) {
        if (cpu != null) {
            cpu.targetColumn = 0xFF;
            cpu.targetRow = 0xFF;
        }
    }

    private void triggerMatrix(int colIndex, int rowIndex) {
        if (cpu != null) {
            cpu.targetColumn = colIndex; 
            cpu.targetRow = rowIndex;    
            cpu.printHit = true; 
            // System.out.printf("UI Thread: Button Pressed. Col:%d Row:%d\n", colIndex, rowIndex);
        }
    }

    @FXML
    public void releaseBtn(MouseEvent event) {
        if (cpu != null) {
            cpu.targetColumn = 0xFF;
            cpu.targetRow = 0xFF;
            // System.out.println("UI Thread: Button Released.");
        }
    }
    
    // --- SERIAL PROTOCOL DECODER ---
    public void receiveSerialFrame(int lowByte, int highByte) {
        
        // --- PHYSICAL HARDWARE INJECTION ---
        // Push the raw byte pair directly out the USB-to-RS232 converter
        if (comPort != null && comPort.isOpen()) {
            byte[] outBuffer = new byte[] { (byte) lowByte, (byte) highByte };
            comPort.writeBytes(outBuffer, 2);
        }
        // -----------------------------------
        
        // High Byte: 1 AAAAA i h[cite: 4, 11]
        // Low Byte:  0 g f e d c b a[cite: 4, 11]
        int address = (highByte >> 2) & 0x1F;
        int iBit = (highByte >> 1) & 0x01;
        int hBit = highByte & 0x01;
        int lower7 = lowByte & 0x7F;
        
        // Combine h (MSB) + lower 7 into the 8-bit Data Payload
        int data = (hBit << 7) | lower7;

        // --- LOGIC ANALYZER & FILTER ---
        int full9BitPayload = (iBit << 8) | data;
        int bankIdx = address / 4; // 0 to 7
        int wordIdx = address % 4; // 0 to 3 (Word 1 to 4)
        
        // Check for Bank Activation via Word 4's 'i' bit
        if (wordIdx == 3) {
            boolean isNowActive = (iBit == 1);
            // If the bank's active state changes (ON->OFF or OFF->ON), intentionally invalidate 
            // the cached states of Words 1, 2, and 3 so they trigger the filter on the next pass.
            if (isNowActive != bankActive[bankIdx]) {
                addressState[bankIdx * 4] = -1;     // Word 1
                addressState[bankIdx * 4 + 1] = -1; // Word 2
                addressState[bankIdx * 4 + 2] = -1; // Word 3
            }
            bankActive[bankIdx] = isNowActive;
        }
        
        boolean hasChanged = (addressState[address] != full9BitPayload);
        if (hasChanged) {
            addressState[address] = full9BitPayload;
        }
        
        // Drop the frame immediately if the filter is on and nothing changed
        if (showChangesOnly && !hasChanged) {
            return; 
        }
        
        boolean currentBankIsActive = bankActive[bankIdx];
        // -------------------------------

        // Decode Address to GBBWW (1-Indexed)
        int word = wordIdx + 1;
        int bank = (bankIdx % 4) + 1;
        int group = (address / 16) + 1;
        String gbbww = String.format("%d%d%d", group, bank, word);

        // Contextual Payload Formatting
        String payloadStr;
        if (word == 1 || word == 2) {
            payloadStr = String.format("%d %02X      ", iBit, data);
        } else {
            StringBuilder bits = new StringBuilder();
            for (int bit = 7; bit >= 0; bit--) {
                bits.append((data & (1 << bit)) != 0 ? (char)('a' + bit) : '.');
            }
            payloadStr = String.format("%d %s", iBit, bits.toString());
        }

        // Query the dictionary and construct the string
        String desc = gbwDictionary.getOrDefault(gbbww, "Unknown Address");
        String timestamp = LocalTime.now().format(timeFmt);
        String hexRaw = String.format("%02X %02X", lowByte, highByte);
        
        // Formatted line without the newline character (ListView handles vertical stacking)
        String line = String.format("%-12s | %-5s | %-3s | %-10s | %s", 
                timestamp, hexRaw, gbbww, payloadStr, desc);

        // Push to UI via JavaFX Thread (throttled to 100 lines)
        Platform.runLater(() -> {
            if (!isLogPaused) {
                logLinesList.add(new LogEntry(line, currentBankIsActive));
                if (logLinesList.size() > 100) {
                    logLinesList.remove(0);
                }
                serialLog.scrollTo(logLinesList.size() - 1); // Auto-scroll to bottom
            }
        });
    }
    
    // --- LED HARDWARE BINDINGS ---
    public void updateHandheldLEDs(boolean gcOn, boolean scOn) {
        Platform.runLater(() -> {
            if (gameClockLed != null) {
                gameClockLed.setFill(gcOn ? Color.RED : Color.DARKRED);
            }
            if (shotClockLed != null) {
                shotClockLed.setFill(scOn ? Color.RED : Color.DARKRED);
            }
            
            // Mimic hardware circuit restriction: Disable the invalid button state
            if (btnRun != null) btnRun.setDisable(gcOn);
            if (btnStop != null) btnStop.setDisable(!gcOn);
        });
    }
    
    private void spawnHandheldWindows() {
        try {
            // Close existing handhelds
            if (gcStage != null && gcStage.isShowing()) gcStage.close();
            if (pcStage != null && pcStage.isShowing()) pcStage.close();

            // --- MAIN TIMER (GAME CLOCK) HANDHELD ---
            if (activeProfile != null && !activeProfile.handheldMainTimer.equalsIgnoreCase("None")) {
                javafx.fxml.FXMLLoader gcLoader = new javafx.fxml.FXMLLoader(getClass().getResource(activeProfile.handheldMainTimer));
                gcLoader.setController(this); 
                Pane gcRoot = gcLoader.load();
                
                if (runningLight != null) {
                    gameClockLed = runningLight; 
                    runningLight = null; 
                }

                gcStage = new Stage();
                gcStage.setTitle("Game Clock");
                gcStage.setScene(new Scene(gcRoot));
                gcStage.setX(100); gcStage.setY(450);
                gcStage.show();
            }

            // --- SECONDARY (SHOT / PLAY CLOCK) HANDHELD ---
            if (activeProfile != null && !activeProfile.handheldAux.equalsIgnoreCase("None")) {
                javafx.fxml.FXMLLoader pcLoader = new javafx.fxml.FXMLLoader(getClass().getResource(activeProfile.handheldAux));
                pcLoader.setController(this);
                Pane pcRoot = pcLoader.load();
                
                if (runningLight != null) {
                    shotClockLed = runningLight;
                    runningLight = null;
                }

                pcStage = new Stage();
                pcStage.setTitle("Aux Clock");
                pcStage.setScene(new Scene(pcRoot));
                pcStage.setX(320); pcStage.setY(450);
                pcStage.show();
            }

        } catch (Exception e) {
            System.err.println("Failed to spawn handhelds.");
            e.printStackTrace();
        }
    }
    
    private void ensureNvramDir() {
        java.io.File dir = new java.io.File("nvram");
        if (!dir.exists()) {
            dir.mkdir();
        }
    }
}