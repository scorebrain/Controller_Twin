/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.emech.mp1802emulator;

/**
 *
 * @author CMcMichael
 */
public class Cosmac1802 {
    
    // 16 general-purpose 16-bit registers
    public int[] R = new int[16];
    
    // 8-bit Data Register (Accumulator)
    public int D = 0;
    
    // Program Counter Pointer (4-bit)
    public int P = 0; 
    
    // Register Selecter (4-bit)
    public int X = 0;
    
    // 1-bit interrupt enable register
    public int IE = 0;
    
    // 1-bit testable output register
    public int Q = 0;
    
    // 1-bit carry register
    public int DF = 0;
    
    // 8-bit temporary register
    public int T = 0;

    // A simple byte array to represent our Memory for Phase 1
    public int[] memory = new int[65536];
    
    public boolean isIdle = false;
    
    // Tracks the last known state of output ports 1-7
    public int[] lastOut = new int[8];
    
    // Hardware timer state
    public int ef2State = 1;
    
    public int lastEf2State = 1;
    
    public boolean interruptPending = false;
    
    // Keypad tracking (Must be volatile for cross-thread visibility!)
    public volatile int lastOut3 = 0;           // Tracks the column mask  
    public volatile int lastOut6 = 0;
    public volatile int targetColumn = 0xFF;    // Which OUT 3 value powers our button
    public volatile int targetRow = 0xFF;       // Which INP 3 value our button pulls low
    public volatile boolean printHit = false;   // Ensures we only print once per click
    public volatile int jumperState = 0xFF;     // Default to unpopulated
    //public volatile int handheldState = 0xFF; // Handhelds on INP 6
    
    // Hardware LED Timers (Software Phosphor)
    public int gcLedTimer = 0;
    public int scLedTimer = 0;
    public boolean lastGcLed = false;
    public boolean lastScLed = false;
    
    // Virtual UART Receiver State
    public int rxState = 0;     // 0=IDLE, 1=START, 2=DATA, 3=STOP
    public int rxCounter = 0;   // Countdown timer for 93 cycles per bit @ 2400 baud
    public int rxBitIndex = 0;
    public int rxByte = 0;
    public int rxLowByte = -1;  // Holds the Low Byte while waiting for the High Byte
    public long totalCycles = 0;
    
    public int lastQ = 1; // Standard UART TTL idles High (1)

    // Reference to the visual UI
    public ConsoleController controller;
    
    // Internal LCD State
    public char[] lcdLine1 = "                ".toCharArray();
    public char[] lcdLine2 = "                ".toCharArray();
    public int lcdCursor = 0; // 0-15 for Line 1, 16-31 for Line 2

    public int input(int port) {
        if (port == 2) return 0x00; 
        //if (port == 6) return handheldState; // Serve handheld switch state
        if (port == 7) return jumperState; 

        // THE TRUE MATRIX SCANNER
        if (port == 3) {
            if (targetRow != 0xFF && targetRow < 5) {
                // The OS explicitly outputs 1E, 1D, 1B, 17, 0F for rows 0 through 4
                if ((lastOut3 & (1 << targetRow)) == 0) {
                    return ~(1 << targetColumn) & 0xFF; 
                }
            }
            return 0xFF; // Open circuit
        }

        // THE WATCHDOG PACIFIER
        if (port == 4) {
            // Returning 0x00 ensures bits 6 and 7 are clear, passing the ANI C0 trap!
            return 0x00; 
        }
        
        return 0xFF; 
    }

    public void output(int port, int value) {
        lastOut[port] = value;
        
        if (port == 3) {
            lastOut3 = value;
            
            // Game Clock LED on Bit 7 (0x80)
            if ((value & 0x80) != 0) {
                gcLedTimer = 6000; // Keep alive slightly longer than 1 frame (4096 cycles)
                if (!lastGcLed) {
                    lastGcLed = true;
                    if (controller != null) controller.updateHandheldLEDs(lastGcLed, lastScLed);
                }
            }
            
            // Shot Clock LED (Assuming Bit 6 / 0x40)
            if ((value & 0x40) != 0) {
                scLedTimer = 6000; 
                if (!lastScLed) {
                    lastScLed = true;
                    if (controller != null) controller.updateHandheldLEDs(lastGcLed, lastScLed);
                }
            }
        }
        if (port == 6) lastOut6 = value;
        // Trigger the LED updates when the OS pushes to OUT 7
        if (port == 7) {
            if (controller != null) controller.updateHandheldLEDs(lastGcLed, lastScLed);
        }

        if (port == 2) {
            if (value == 0x01) {
                for(int i = 0; i < 16; i++) { lcdLine1[i] = ' '; lcdLine2[i] = ' '; }
                lcdCursor = 0;
            } else if (value >= 0x80 && value <= 0x8F) {
                lcdCursor = value & 0x0F;
            } else if (value >= 0xC0 && value <= 0xCF) {
                lcdCursor = 16 + (value & 0x0F);
            }
            if (controller != null) controller.updateLCD(new String(lcdLine1), new String(lcdLine2));
        } else if (port == 1) {
            char c = (char)value;
            if (lcdCursor < 16) lcdLine1[lcdCursor] = c;
            else if (lcdCursor >= 16 && lcdCursor < 32) lcdLine2[lcdCursor - 16] = c;
            lcdCursor++;
            if (controller != null) controller.updateLCD(new String(lcdLine1), new String(lcdLine2));
        }
    }

    public int getEF1() {
        // EF1: Global Matrix Sense Line
        // Drops unconditionally to wake the OS the millisecond a button is held!
        return (targetRow != 0xFF) ? 0 : 1; 
    }
    public int getEF2() { return ef2State; }
    public int getEF3() { return 1; }
    public int getEF4() { return 1; }
    
    public void reset() {
        P = 0;
        R[0] = 0;
        D = 0;
    }

    /**
     * Executes a single machine cycle.
     */
    public void step() {
        // --- THE RISING EDGE FIX ---
        // The OS interrupt handler explicitly checks if EF2 is 1. 
        // If we trigger on 0, it aborts the matrix scan entirely!
        if (lastEf2State == 0 && ef2State == 1) {
            if (R[1] != 0) { 
                interruptPending = true;
            }
        }
        lastEf2State = ef2State;

        // 0. HANDLE HARDWARE INTERRUPTS
        if (interruptPending && IE == 1) {
            T = (X << 4) | P;
            P = 1;
            X = 2;
            IE = 0;
            interruptPending = false;
        }
        
        // 1. FETCH
        int pc = R[P];              
        int opcode = memory[pc];    

        R[P] = (R[P] + 1) & 0xFFFF;
        
        // --- CYCLE CALCULATOR ---
        int cycles = ((opcode & 0xF0) == 0xC0) ? 3 : 2;
        totalCycles += cycles;
        // ------------------------
        
        // --- LED PHOSPHOR DECAY ---
        if (gcLedTimer > 0) {
            gcLedTimer -= cycles;
            if (gcLedTimer <= 0 && lastGcLed) {
                lastGcLed = false;
                if (controller != null) controller.updateHandheldLEDs(lastGcLed, lastScLed);
            }
        }
        if (scLedTimer > 0) {
            scLedTimer -= cycles;
            if (scLedTimer <= 0 && lastScLed) {
                lastScLed = false;
                if (controller != null) controller.updateHandheldLEDs(lastGcLed, lastScLed);
            }
        }
        
        // 2. DECODE
        int I = (opcode >> 4) & 0x0F; 
        int N = opcode & 0x0F;
        
        // 3. EXECUTE
        switch (I) {
            
            case 0x0:
                if (N == 0) {
                    // IDL (Idle): The B23 OS never sleeps. 
                    // If it hits this, it crashed into a data table. Park the PC.
                    R[P] = (R[P] - 1) & 0xFFFF;
                } else {
                    D = memory[R[N]];
                }
                break;
                
            case 0x1: R[N] = (R[N] + 1) & 0xFFFF; break;
            case 0x2: R[N] = (R[N] - 1) & 0xFFFF; break;
                
            case 0x3:
                int target = memory[R[P]];
                R[P] = (R[P] + 1) & 0xFFFF; 
                
                boolean branchTaken = false;
                switch (N) {
                    case 0x0: branchTaken = true; break; 
                    case 0x1: branchTaken = (Q == 1); break; 
                    case 0x2: branchTaken = (D == 0); break; 
                    case 0x3: branchTaken = (DF == 1); break; 
                    case 0x4: branchTaken = (getEF1() == 1); break; 
                    case 0x5: branchTaken = (getEF2() == 1); break; 
                    case 0x6: branchTaken = (getEF3() == 1); break; 
                    case 0x7: branchTaken = (getEF4() == 1); break; 
                    case 0x9: branchTaken = (Q == 0); break; 
                    case 0xA: branchTaken = (D != 0); break; 
                    case 0xB: branchTaken = (DF == 0); break; 
                    case 0xC: branchTaken = (getEF1() == 0); break; 
                    case 0xD: branchTaken = (getEF2() == 0); break; 
                    case 0xE: branchTaken = (getEF3() == 0); break; 
                    case 0xF: branchTaken = (getEF4() == 0); break; 
                    default: throw new UnsupportedOperationException(String.format("Unimplemented short branch: 3%X", N));
                }
                
                if (branchTaken) {
                    R[P] = (R[P] & 0xFF00) | target;
                }
                break;
                
            case 0x4:
                D = memory[R[N]];
                R[N] = (R[N] + 1) & 0xFFFF;
                break;
                
            case 0x5:
                memory[R[N]] = D;
                break;
                
            case 0x6:
                if (N == 0x0) {
                    R[X] = (R[X] + 1) & 0xFFFF;
                } else if (N >= 1 && N <= 7) {
                    int port = N;
                    int outData = memory[R[X]];
                    output(port, outData);
                    R[X] = (R[X] + 1) & 0xFFFF;
                } else if (N >= 9 && N <= 0xF) {
                    int port = N & 0x07; 
                    int inData = input(port);
                    memory[R[X]] = inData;
                    D = inData;
                } else {
                    throw new UnsupportedOperationException(String.format("Unimplemented opcode: 6%X", N));
                }
                break;
                
            case 0x7:
            case 0xF:
                int operand = 0;
                
                // CRITICAL FIX: Only fetch memory operands for these specific ALU instructions
                if (opcode == 0xF0 || opcode == 0xF1 || opcode == 0xF2 || opcode == 0xF3 || 
                    opcode == 0xF4 || opcode == 0xF5 || opcode == 0xF7 ||
                    opcode == 0x74 || opcode == 0x75 || opcode == 0x77) {
                    operand = memory[R[X]];
                }
                // CRITICAL FIX: Only fetch immediate operands and advance PC for these specific ALU instructions
                else if (opcode == 0xF8 || opcode == 0xF9 || opcode == 0xFA || opcode == 0xFB || 
                         opcode == 0xFC || opcode == 0xFD || opcode == 0xFF ||
                         opcode == 0x7C || opcode == 0x7D || opcode == 0x7F) {
                    operand = memory[R[P]];
                    R[P] = (R[P] + 1) & 0xFFFF;
                }
                
                switch (opcode) {
                    case 0xF8: D = operand; break; 
                    case 0xF0: D = operand; break; 
                    case 0xF4: case 0xFC: 
                        int addRes = D + operand;
                        DF = (addRes > 255) ? 1 : 0;
                        D = addRes & 0xFF;
                        break;
                    case 0x74: case 0x7C: 
                        int adcRes = D + operand + DF;
                        DF = (adcRes > 255) ? 1 : 0;
                        D = adcRes & 0xFF;
                        break;
                    case 0xF5: case 0xFD:
                        int sdRes = operand - D;
                        DF = (sdRes >= 0) ? 1 : 0;
                        D = sdRes & 0xFF;
                        break;
                    case 0x75: case 0x7D: 
                        int sdbRes = operand - D - (DF == 0 ? 1 : 0);
                        DF = (sdbRes >= 0) ? 1 : 0;
                        D = sdbRes & 0xFF;
                        break;
                    case 0xF7: case 0xFF:
                        int smRes = D - operand;
                        DF = (smRes >= 0) ? 1 : 0;
                        D = smRes & 0xFF;
                        break;
                    case 0x77: case 0x7F: 
                        int smbRes = D - operand - (DF == 0 ? 1 : 0);
                        DF = (smbRes >= 0) ? 1 : 0;
                        D = smbRes & 0xFF;
                        break;
                    case 0x78: // SAV
                        memory[R[X]] = T;
                        break;
                    case 0xF1: case 0xF9: D = D | operand; break; 
                    case 0xF2: case 0xFA: D = D & operand; break; 
                    case 0xF3: case 0xFB: D = D ^ operand; break; 
                    case 0xF6: 
                        DF = D & 0x01;
                        D = (D >> 1) & 0x7F;
                        break;
                    case 0x76: 
                        int newDfR = D & 0x01;
                        D = ((D >> 1) & 0x7F) | (DF << 7);
                        DF = newDfR;
                        break;
                    case 0xFE: 
                        DF = (D >> 7) & 0x01;
                        D = (D << 1) & 0xFE;
                        break;
                    case 0x7E: 
                        int newDfL = (D >> 7) & 0x01;
                        D = ((D << 1) & 0xFE) | DF;
                        DF = newDfL;
                        break;
                    case 0x70: // RET
                        int retData = memory[R[X]];
                        R[X] = (R[X] + 1) & 0xFFFF; // CRITICAL FIX: Increment the old stack pointer!
                        X = (retData >> 4) & 0x0F;
                        P = retData & 0x0F;
                        IE = 1;
                        break;
                    case 0x71: // DIS
                        int disData = memory[R[X]];
                        R[X] = (R[X] + 1) & 0xFFFF; // CRITICAL FIX: Increment the old stack pointer!
                        X = (disData >> 4) & 0x0F;
                        P = disData & 0x0F;
                        IE = 0;
                        break;
                    case 0x72: 
                        D = memory[R[X]];
                        R[X] = (R[X] + 1) & 0xFFFF;
                        break;
                    case 0x73: 
                        memory[R[X]] = D;
                        R[X] = (R[X] - 1) & 0xFFFF;
                        break;
                    case 0x7A: Q = 0; break;
                    case 0x7B: Q = 1; break;
                    default: throw new UnsupportedOperationException(String.format("Unimplemented opcode: %02X", opcode));
                }
                break;
                
            case 0x8: D = R[N] & 0x00FF; break;
            case 0x9: D = (R[N] >> 8) & 0x00FF; break;
            case 0xA: R[N] = (R[N] & 0xFF00) | D; break;
            case 0xB: R[N] = (D << 8) | (R[N] & 0x00FF); break;
                
            case 0xC:
                if (N == 0x4) { break; } 
                else if (N == 0x8) {
                    R[P] = (R[P] + 2) & 0xFFFF;
                    break;
                }
                
                int targetHigh = memory[R[P]];
                R[P] = (R[P] + 1) & 0xFFFF;
                int targetLow = memory[R[P]];
                R[P] = (R[P] + 1) & 0xFFFF;
                int longTarget = (targetHigh << 8) | targetLow;
                boolean longBranchTaken = false;
                
                switch (N) {
                    case 0x0: longBranchTaken = true; break; 
                    case 0x1: longBranchTaken = (Q == 1); break; 
                    case 0x2: longBranchTaken = (D == 0); break; 
                    case 0x3: longBranchTaken = (DF == 1); break; 
                    case 0x9: longBranchTaken = (Q == 0); break; 
                    case 0xA: longBranchTaken = (D != 0); break; 
                    case 0xB: longBranchTaken = (DF == 0); break; 
                    default: throw new UnsupportedOperationException(String.format("Unimplemented Long Branch: C%X", N));
                }
                
                if (longBranchTaken) {
                    R[P] = longTarget; 
                }
                break;
                
            case 0xD: P = N; break;
            case 0xE: X = N; break;
                
            default:
                throw new UnsupportedOperationException(String.format("Unimplemented opcode: %02X", opcode));
        }
        
        // Remove this block to stop the console spam!
        // if (Q != lastQ) {
        //     System.out.print(Q == 1 ? "^" : "_");
        //     lastQ = Q;
        // }
        
        // --- VIRTUAL 2400-BAUD UART RECEIVER ---
        // 223,744 cycles/sec / 2400 baud = ~93.2 cycles per bit
        if (rxState == 0) {
            // Detect START bit (Falling Edge: 1 -> 0)
            if (lastQ == 1 && Q == 0) { 
                rxState = 1;
                rxCounter = 46; // Wait half a bit (46 cycles) to sample the exact center
            }
        } else {
            rxCounter -= cycles; // Subtract ACTUAL machine cycles elapsed
            if (rxCounter <= 0) {
                if (rxState == 1) { // Confirm START bit
                    if (Q == 0) {
                        rxState = 2;
                        rxCounter += 93; // Carry over overshoot for perfect phase alignment
                        rxBitIndex = 0;
                        rxByte = 0;
                    } else {
                        rxState = 0; // False alarm (Glitch)
                    }
                } else if (rxState == 2) { // Read 8 DATA bits (LSB first)
                    rxByte |= (Q << rxBitIndex);
                    rxBitIndex++;
                    rxCounter += 93;
                    if (rxBitIndex >= 8) {
                        rxState = 3;
                    }
                } else if (rxState == 3) { // STOP bit
                    processSerialByte(rxByte);
                    rxState = 0; // Return to IDLE
                }
            }
        }
        lastQ = Q;
    }

    // --- SCOREBOARD FRAME ASSEMBLER ---
    public void processSerialByte(int b) {
        if ((b & 0x80) == 0) {
            // MSB is 0: This is the Low Byte[cite: 4, 11]
            rxLowByte = b; 
        } else {
            // MSB is 1: This is the High Byte[cite: 4, 11]
            if (rxLowByte != -1) {
                if (controller != null) {
                    controller.receiveSerialFrame(rxLowByte, b);
                }
                rxLowByte = -1; // Reset until the next Low Byte arrives
            }
        }
    }
    
    // --- NON-VOLATILE RAM (BATTERY BACKUP) ---
    public void saveNVRAM(String filename) {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(filename)) {
            for (int i = 0; i < memory.length; i++) {
                fos.write(memory[i]);
            }
        } catch (Exception e) {
            System.err.println("Failed to save NVRAM: " + e.getMessage());
        }
    }

    public void loadNVRAM(String filename) {
        java.io.File file = new java.io.File(filename);
        if (file.exists()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                for (int i = 0; i < memory.length; i++) {
                    int b = fis.read();
                    if (b == -1) break;
                    memory[i] = b;
                }
                System.out.println("NVRAM restored from " + filename);
            } catch (Exception e) {
                System.err.println("Failed to load NVRAM: " + e.getMessage());
            }
        } else {
            System.out.println("Cold boot: No previous NVRAM found.");
        }
    }
}
