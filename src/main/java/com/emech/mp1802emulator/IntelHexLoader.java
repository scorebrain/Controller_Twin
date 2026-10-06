/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.emech.mp1802emulator;

/**
 *
 * @author CMcMichael
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class IntelHexLoader {

    /**
     * Parses an Intel HEX file and loads the data into the provided memory array.
     * @param filePath
     * @param memory
     * @throws java.io.IOException
     */
    public static void load(String filePath, int[] memory) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        
        for (String line : lines) {
            // Skip empty lines or anything that doesn't start with the record mark
            if (line == null || line.trim().isEmpty() || !line.startsWith(":")) {
                continue;
            }
            
            // Extract the byte count, address, and record type
            int byteCount = Integer.parseInt(line.substring(1, 3), 16);
            int address = Integer.parseInt(line.substring(3, 7), 16);
            int recordType = Integer.parseInt(line.substring(7, 9), 16);
            
            if (recordType == 0x00) {
                // Data Record: slice out the data bytes and write to memory
                for (int i = 0; i < byteCount; i++) {
                    int startIndex = 9 + (i * 2);
                    int dataByte = Integer.parseInt(line.substring(startIndex, startIndex + 2), 16);
                    memory[address + i] = dataByte;
                }
            } else if (recordType == 0x01) {
                // End of File Record
                break;
            }
        }
    }
}
