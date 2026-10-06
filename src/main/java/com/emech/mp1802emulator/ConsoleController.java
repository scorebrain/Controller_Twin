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
import javafx.scene.control.TextArea;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.CheckBox;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class ConsoleController {
    
    private Cosmac1802 cpu;
    private HashMap<String, String> gbwDictionary = new HashMap<>();
    private SerialPort comPort; // Add the hardware port variable
    private boolean enableHardwareCom = true; // Set to true when USB adapter is attached
    private DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private StringBuilder logBuffer = new StringBuilder();
    
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

    @FXML
    private Label line1Label;

    @FXML
    private Label line2Label;
    
    @FXML
    private javafx.scene.control.CheckBox jumperCheckBox;

    @FXML
    public void initialize() {
        cpu = new Cosmac1802();
        cpu.controller = this; // Link CPU to this controller
        cpu.reset();
        
        // --- RESTORE HARDWARE STATE ---
        // Load the battery-backed RAM first
        cpu.loadNVRAM("B23.nvram");

        try {
            IntelHexLoader.load("B23.HEX", cpu.memory);
        } catch (Exception e) {
            System.err.println("Failed to load ROM: " + e.getMessage());
            return;
        }
        
        // Load the external address descriptions
        loadProtocolDictionary("Protocol.ini", "B23_DEFAULT");
       
        // --- INITIALIZE PHYSICAL RS-232 HARDWARE ---
        if (enableHardwareCom) {
            comPort = SerialPort.getCommPort("COM3");
            // B23 Spec: 2400 baud, 8 data bits, 1 stop bit, no parity
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
                // --- SIMULATE POWER LOSS (SAVE RAM) ---
                if (cpu != null) {
                    cpu.saveNVRAM("B23.nvram");
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

        // Start the CPU in a background daemon thread
        Thread cpuThread = new Thread(() -> {
            long startTime = System.nanoTime();
            // 1.79 MHz clock / 8 ticks per machine cycle = 223,721.5 Hz
            // 1,000,000,000 ns / 223,721.5 = 4469.84 ns per cycle
            final double NS_PER_CYCLE = 4469.84;
            long lastThrottleCheck = 0;
            
            try {
                while (true) {
                    cpu.step();
                    
                    // EF2 Hardware Oscillator: Toggle state every 2048 machine cycles
                    cpu.ef2State = ((cpu.totalCycles / 2048) % 2 == 0) ? 1 : 0;
                    
                    // Self-correcting real-time throttle
                    if (cpu.totalCycles - lastThrottleCheck >= 10000) {
                        lastThrottleCheck = cpu.totalCycles;
                        long expectedElapsedNs = (long) (cpu.totalCycles * NS_PER_CYCLE);
                        long actualElapsedNs = System.nanoTime() - startTime;
                        long differenceNs = expectedElapsedNs - actualElapsedNs;
                        
                        // If the emulator is ahead of real time by > 2ms, sleep
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
            line1Label.setText(line1);
            line2Label.setText(line2);
        });
    }
   
    @FXML
    public void handleJumperToggle(javafx.event.ActionEvent event) {
        if (cpu != null) {
            cpu.jumperState = jumperCheckBox.isSelected() ? 0x00 : 0xFF;
        }
    }

    // The Universal Button Press Handler
    // 8 columns (0 - 7) x 5 rows (0=Row B, 1=Row C, 2 = Row D, 3=Row E, 5=Row F)
    @FXML
    public void handleMatrixPress(MouseEvent event) {
        if (cpu != null) {
            javafx.scene.control.Button btn = (javafx.scene.control.Button) event.getSource();
            String data = (String) btn.getUserData();
            
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
        });
    }
    
    private void spawnHandheldWindows() {
        // --- GAME CLOCK HANDHELD ---
        VBox gcLayout = new VBox(20);
        gcLayout.setAlignment(Pos.CENTER);
        gcLayout.setStyle("-fx-background-color: #e6e6e6; -fx-border-color: #d1b2b2; -fx-border-width: 2; -fx-border-radius: 10; -fx-background-radius: 10;");
        gcLayout.setPadding(new Insets(20));

        gameClockLed = new Circle(8, Color.DARKRED);
        gameClockLed.setStroke(Color.GRAY);

        // ... (Game Clock LED definition)
        Button gcBtn = new Button();
        gcBtn.setPrefSize(40, 40);
        gcBtn.setStyle("-fx-background-color: #333333; -fx-background-radius: 20;");
        // Switch #1 -> Row 4, Col 7
        gcBtn.setOnMousePressed(e -> { if (cpu != null) { cpu.targetColumn = 7; cpu.targetRow = 4; } });
        gcBtn.setOnMouseReleased(e -> { if (cpu != null) { cpu.targetColumn = 0xFF; cpu.targetRow = 0xFF; } });

        Label gcLabel = new Label("GAME CLOCK\nWIRED CONTROLLER");
        gcLabel.setStyle("-fx-text-fill: #0000aa; -fx-font-weight: bold; -fx-text-alignment: center;");
        
        gcLayout.getChildren().addAll(gameClockLed, gcBtn, gcLabel);

        Stage gcStage = new Stage();
        gcStage.setTitle("Game Clock");
        gcStage.setScene(new Scene(gcLayout, 200, 300));
        gcStage.setX(100); gcStage.setY(450);
        gcStage.show();

        // --- SHOT CLOCK HANDHELD ---
        VBox scLayout = new VBox(15);
        scLayout.setAlignment(Pos.CENTER);
        scLayout.setStyle("-fx-background-color: #e6e6e6; -fx-border-color: #d1b2b2; -fx-border-width: 2; -fx-border-radius: 10; -fx-background-radius: 10;");
        scLayout.setPadding(new Insets(20));

        shotClockLed = new Circle(8, Color.DARKRED);
        shotClockLed.setStroke(Color.GRAY);

        Button resetBtn = new Button();
        resetBtn.setPrefSize(40, 40);
        resetBtn.setStyle("-fx-background-color: #333333; -fx-background-radius: 20;");
        // Switch #3 (RESET) -> Row 4, Col 1
        resetBtn.setOnMousePressed(e -> { if (cpu != null) { cpu.targetColumn = 1; cpu.targetRow = 4; } });
        resetBtn.setOnMouseReleased(e -> { if (cpu != null) { cpu.targetColumn = 0xFF; cpu.targetRow = 0xFF; } });
        VBox resetBox = new VBox(2, new Label("RESET"), resetBtn);
        resetBox.setAlignment(Pos.CENTER);
        resetBox.setStyle("-fx-border-color: #0000aa; -fx-border-width: 2; -fx-padding: 5;");
        ((Label)resetBox.getChildren().get(0)).setStyle("-fx-text-fill: #0000aa; -fx-font-weight: bold;");

        Button resetObBtn = new Button();
        resetObBtn.setPrefSize(40, 40);
        resetObBtn.setStyle("-fx-background-color: #333333; -fx-background-radius: 20;");
        // Switch #4 (RESET-OB) -> Row 4, Col 3
        resetObBtn.setOnMousePressed(e -> { if (cpu != null) { cpu.targetColumn = 3; cpu.targetRow = 4; } });
        resetObBtn.setOnMouseReleased(e -> { if (cpu != null) { cpu.targetColumn = 0xFF; cpu.targetRow = 0xFF; } });
        VBox resetObBox = new VBox(2, new Label("RESET - OB / BLANK"), resetObBtn);
        resetObBox.setAlignment(Pos.CENTER);
        resetObBox.setStyle("-fx-border-color: #0000aa; -fx-border-width: 2; -fx-padding: 5;");
        ((Label)resetObBox.getChildren().get(0)).setStyle("-fx-text-fill: #0000aa; -fx-font-weight: bold;");

        Button blankBtn = new Button();
        blankBtn.setPrefSize(40, 40);
        blankBtn.setStyle("-fx-background-color: #333333; -fx-background-radius: 20;");
        // Switch #2 (BLANK) -> Row 4, Col 5
        blankBtn.setOnMousePressed(e -> { if (cpu != null) { cpu.targetColumn = 5; cpu.targetRow = 4; } });
        blankBtn.setOnMouseReleased(e -> { if (cpu != null) { cpu.targetColumn = 0xFF; cpu.targetRow = 0xFF; } });
        VBox blankBox = new VBox(2, new Label("PAUSE"), blankBtn);
        blankBox.setAlignment(Pos.CENTER);
        blankBox.setStyle("-fx-border-color: red; -fx-border-width: 2; -fx-padding: 5;");
        ((Label)blankBox.getChildren().get(0)).setStyle("-fx-text-fill: red; -fx-font-weight: bold;");

        Label scLabel = new Label("SHOT TIMER\nWIRED CONTROLLER");
        scLabel.setStyle("-fx-text-fill: #0000aa; -fx-font-weight: bold; -fx-text-alignment: center;");

        scLayout.getChildren().addAll(shotClockLed, resetBox, resetObBox, blankBox, scLabel);

        Stage scStage = new Stage();
        scStage.setTitle("Shot Clock");
        scStage.setScene(new Scene(scLayout, 220, 450));
        scStage.setX(320); scStage.setY(450);
        scStage.show();
    }
}