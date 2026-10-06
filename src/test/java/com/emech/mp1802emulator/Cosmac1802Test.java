/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/UnitTests/JUnit5TestClass.java to edit this template
 */
package com.emech.mp1802emulator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Cosmac1802Test {

    @Test
    public void testElectroMechBootSequence() {
        Cosmac1802 cpu = new Cosmac1802();
        cpu.reset();
        
        // Inject the first 10 bytes of B23.hex into memory
        int[] bootrom = {0x71, 0x00, 0x7B, 0xF8, 0x00, 0xB3, 0xF8, 0x09, 0xA3, 0xD3};
        for(int i = 0; i < bootrom.length; i++) {
            cpu.memory[i] = bootrom[i];
        }
        
        // Execute exactly 7 instructions (which consumes the 10 bytes)
        for(int i = 0; i < 7; i++) {
            cpu.step();
        }
        
        // Assert the CPU state matches the hardware reality
        assertEquals(0, cpu.IE, "Interrupts should be disabled (DIS)");
        assertEquals(1, cpu.Q, "Q pin should be high (SEQ)");
        assertEquals(0x0009, cpu.R[3], "Register 3 should hold address 0x0009");
        assertEquals(3, cpu.P, "Program Counter Pointer (P) should be set to 3");
    }
    
    @Test
    public void testShortBranches() {
        Cosmac1802 cpu = new Cosmac1802();
        cpu.reset();
        
        // Custom program to test BZ (Branch on Zero) and BNZ (Branch on Not Zero)
        int[] program = {
            0xF8, 0x00, // 0000: LDI 00  (D = 0)
            0x32, 0x06, // 0002: BZ 06   (Branch to 0006 because D == 0)
            0x00, 0x00, // 0004: IDL     (Should be skipped)
            0xF8, 0x01, // 0006: LDI 01  (D = 1)
            0x3A, 0x0C, // 0008: BNZ 0C  (Branch to 000C because D != 0)
            0x00, 0x00, // 000A: IDL     (Should be skipped)
            0x7B        // 000C: SEQ     (Set Q to 1)
        };
        
        for(int i = 0; i < program.length; i++) {
            cpu.memory[i] = program[i];
        }
        
        // Execute LDI, BZ, LDI, BNZ, SEQ (5 instructions)
        for(int i = 0; i < 5; i++) {
            cpu.step();
        }
        
        assertEquals(0x000D, cpu.R[0], "PC should have advanced just past the SEQ instruction");
        assertEquals(1, cpu.D, "D should be 1 from the second LDI");
        assertEquals(1, cpu.Q, "Q should be 1, proving the branches hit the SEQ instruction");
    }
    
    @Test
    public void testALU() {
        Cosmac1802 cpu = new Cosmac1802();
        cpu.reset();
        
        int[] program = {
            0xF8, 0x50, // 0000: LDI 0x50   (D = 0x50)
            0xFC, 0x20, // 0002: ADI 0x20   (D = 0x50 + 0x20 = 0x70, DF = 0)
            0xFF, 0x80, // 0004: SMI 0x80   (D = 0x70 - 0x80 = 0xF0, DF = 0 for borrow)
            0xFE,       // 0006: SHL        (D = 0xE0, DF = 1)
            0x7C, 0x20  // 0007: ADCI 0x20  (D = 0xE0 + 0x20 + 1 (from DF) = 0x01, DF = 1)
        };
        
        for(int i = 0; i < program.length; i++) {
            cpu.memory[i] = program[i];
        }
        
        cpu.step(); // LDI
        assertEquals(0x50, cpu.D);
        
        cpu.step(); // ADI
        assertEquals(0x70, cpu.D);
        assertEquals(0, cpu.DF);
        
        cpu.step(); // SMI
        assertEquals(0xF0, cpu.D);
        assertEquals(0, cpu.DF, "DF should be 0 indicating a borrow occurred");
        
        cpu.step(); // SHL
        assertEquals(0xE0, cpu.D);
        assertEquals(1, cpu.DF, "DF should be 1 because MSB of 0xF0 is 1");
        
        cpu.step(); // ADCI
        assertEquals(0x01, cpu.D, "0xE0 + 0x20 + 1 (from DF) should be 0x01");
        assertEquals(1, cpu.DF, "DF should be 1 indicating a carry out");
    }
}
