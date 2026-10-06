/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.emech.mp1802emulator;

/**
 *
 * @author CMcMichael
 */

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

public class ConsoleController {
    
    private Cosmac1802 cpu;
    private SerialPort comPort; // Add the hardware port variable
    private boolean enableHardwareCom = false; // Set to true when USB adapter is attached
    private DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private StringBuilder logBuffer = new StringBuilder();
    private int logLines = 0;
    private TextArea serialLog;
    private Button pauseBtn;
    private Button copyBtn;
    private boolean isLogPaused = false;

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

       try {
            IntelHexLoader.load("B23.HEX", cpu.memory);
        } catch (Exception e) {
            System.err.println("Failed to load ROM: " + e.getMessage());
            return;
        }
       
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
            }));
        } else {
            System.out.println("PHYSICAL HARDWARE: RS-232 output is disabled.");
        }
        // -------------------------------------------
        
        // --- SPAWN INDEPENDENT SERIAL LOGGER WINDOW ---
        serialLog = new TextArea();
        serialLog.setEditable(false);
        serialLog.setStyle("-fx-control-inner-background: #000000; -fx-text-fill: #00FF00; -fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px; -fx-font-weight: bold;");
        
        pauseBtn = new Button("Pause Log");
        copyBtn = new Button("Copy Log");
        
        pauseBtn.setOnAction(e -> {
            isLogPaused = !isLogPaused;
            pauseBtn.setText(isLogPaused ? "Resume Log" : "Pause Log");
        });
        
        copyBtn.setOnAction(e -> {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(serialLog.getText());
            clipboard.setContent(content);
        });

        HBox buttonBar = new HBox(10, pauseBtn, copyBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(5));
        buttonBar.setStyle("-fx-background-color: #d9d9d9;");

        VBox logRoot = new VBox(serialLog, buttonBar);
        // This command ensures the text area expands, pinning the buttons to the bottom
        VBox.setVgrow(serialLog, Priority.ALWAYS);

        Stage logStage = new Stage();
        logStage.setTitle("B23 Serial Data Trace");
        logStage.setScene(new Scene(logRoot, 500, 300));
        
        // Offset the window slightly so it doesn't spawn directly on top of the console
        logStage.setX(100); 
        logStage.setY(100);
        logStage.show();
        // ----------------------------------------------

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
    
    /*
    // --- SERIAL LOGGER UI HANDLERS ---
    @FXML
    public void handlePauseLog(javafx.event.ActionEvent event) {
        isLogPaused = !isLogPaused;
        if (isLogPaused) {
            pauseBtn.setText("Resume Log");
        } else {
            pauseBtn.setText("Pause Log");
        }
    }

    @FXML
    public void handleCopyLog(javafx.event.ActionEvent event) {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(serialLog.getText());
        clipboard.setContent(content);
        
        // Optional console confirmation
        System.out.println("Serial log copied to clipboard.");
    }
*/
    
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

        // Decode Address to GBBWW (1-Indexed)
        int word = (address % 4) + 1;
        int bank = ((address / 4) % 4) + 1;
        int group = (address / 16) + 1;
        String gbbww = String.format("%d%d%d", group, bank, word);

        // Contextual Payload Formatting
        String dataStr;
        if (word == 1 || word == 2) {
            // Words 1 & 2: Hex/BCD format
            dataStr = String.format("%02X      ", data);
        } else {
            // Words 3 & 4: Discrete bits mapping h down to a
            StringBuilder bits = new StringBuilder();
            for (int bit = 7; bit >= 0; bit--) {
                bits.append((data & (1 << bit)) != 0 ? (char)('a' + bit) : '.');
            }
            dataStr = bits.toString();
        }

        // Basic Description Generator (To be expanded)
        String desc = "---";
        if (address == 0) desc = "Main Timer Seconds";
        else if (address == 1) desc = "Main Timer Minutes";
        else if (address == 3) desc = "Quarters / Colons";
        else if (address == 4) desc = "System / Active Bit";
        else if (address == 9) desc = "Team 2 (Home) Total Points";

        // Construct the Line
        String timestamp = LocalTime.now().format(timeFmt);
        String hexRaw = String.format("%02X %02X", lowByte, highByte);
        String line = String.format("%s | %s | %s | %d | %s | %s\n", 
                timestamp, hexRaw, gbbww, iBit, dataStr, desc);

        // Push to UI via JavaFX Thread (throttled to 100 lines)
        Platform.runLater(() -> {
            if (!isLogPaused) {
                logBuffer.append(line);
                logLines++;
                if (logLines > 100) {
                    int firstNewline = logBuffer.indexOf("\n");
                    if (firstNewline != -1) {
                        logBuffer.delete(0, firstNewline + 1);
                        logLines--;
                    }
                }
                serialLog.setText(logBuffer.toString());
                serialLog.setScrollTop(Double.MAX_VALUE); // Auto-scroll to bottom
            }
        });
    }
}