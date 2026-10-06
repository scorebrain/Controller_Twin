/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/UnitTests/JUnit5TestClass.java to edit this template
 */
package com.emech.mp1802emulator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IntelHexLoaderTest {

    @Test
    public void testLoadB23Hex() {
        Cosmac1802 cpu = new Cosmac1802();
        
        try {
            // Assumes B23.HEX is in the project root directory
            IntelHexLoader.load("B23.HEX", cpu.memory);
        } catch (Exception e) {
            fail("Failed to load HEX file: " + e.getMessage());
        }
        
        // Assert the bootstrap sequence is correctly loaded at 0x0000
        assertEquals(0x71, cpu.memory[0x0000], "Address 0x0000 should be 0x71 (DIS)");
        assertEquals(0x00, cpu.memory[0x0001], "Address 0x0001 should be 0x00");
        assertEquals(0x7B, cpu.memory[0x0002], "Address 0x0002 should be 0x7B (SEQ)");
        
        // Check a byte further down based on the second line of the hex file:
        // :10 0010 00 F8...
        assertEquals(0xF8, cpu.memory[0x0010], "Address 0x0010 should be 0xF8");
    }
}