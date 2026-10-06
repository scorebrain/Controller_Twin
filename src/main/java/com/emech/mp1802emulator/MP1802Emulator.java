/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package com.emech.mp1802emulator;

/**
 *
 * @author CMcMichael
 */
public class MP1802Emulator {

    public static void main(String[] args) {
        Cosmac1802 cpu = new Cosmac1802();
        cpu.reset();

        System.out.println("Loading B23.HEX...");
        try {
            IntelHexLoader.load("B23.HEX", cpu.memory);
            System.out.println("Load complete.");
        } catch (Exception e) {
            System.err.println("Failed to load ROM: " + e.getMessage());
            return;
        }

        System.out.println("Starting CPU execution...");
        long cycleCount = 0;

        try {
            // The main execution loop
            while (true) {
                cpu.step();
                cycleCount++;
                
                // Simulate the 54.625 Hz hardware oscillator tied to EF2
                // Toggling every 2048 cycles creates a full wave every 4096 cycles
                if (cycleCount % 2048 == 0) {
                    cpu.ef2State = (cpu.ef2State == 1) ? 0 : 1;
                }
                
                // Print a heartbeat every 500,000 cycles (roughly every 2 seconds of emulation)
                if (cycleCount % 500000 == 0) {
                    System.out.printf("Heartbeat - PC: %04X | OUT 1: %02X | OUT 2: %02X | OUT 3: %02X | OUT 4: %02X | OUT 5: %02X | OUT 6: %02X | OUT 7: %02X\n", 
                            cpu.R[cpu.P], cpu.lastOut[1], cpu.lastOut[2], cpu.lastOut[3], 
                            cpu.lastOut[4], cpu.lastOut[5], cpu.lastOut[6], cpu.lastOut[7]);
                }
            }
        } catch (Exception e) {
            System.out.println("\n--- EMULATION HALTED ---");
            System.out.println("Cycles executed: " + cycleCount);
            System.out.println("Reason: " + e.getMessage());
            
            // Print the current state of the CPU
            System.out.printf("PC is Register %d. Current PC Address = %04X\n", cpu.P, cpu.R[cpu.P]);
            System.out.printf("D = %02X, DF = %d, Q = %d, IE = %d\n", cpu.D, cpu.DF, cpu.Q, cpu.IE);
            System.out.println("------------------------");
        }
    }
}
