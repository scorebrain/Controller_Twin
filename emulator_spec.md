# Electro-Mech 1802 Console Emulator Specification

## 1. Core Architecture
* **CPU:** Harris CDP1802ACE COSMAC microprocessor.
* **Clock Speed:** 1.79 MHz CPU clock (`CPUCLK`), derived from a 3.58 MHz hardware crystal oscillator[cite: 17].
* **Cycle Time:** 4.47 microseconds per machine cycle[cite: 17].
* **Execution State:** The CPU never sleeps. The `00` bytes in ROM are data tables and ASCII string buffers, not `IDL` instructions[cite: 16]. The OS runs a continuous execution loop.

## 2. Memory Map
The virtual memory bus routes 16-bit addresses as follows:
* **0x0000 - 0x3FFF:** 16K EEPROM (ROM / Program Data)[cite: 17].
* **0x4000 - 0x5FFF:** 8K Aux Select[cite: 17].
* **0x6000 - 0x7FFF:** 8K Aux Select[cite: 17].
* **0x8000 - 0xFFFF:** 32K RAM[cite: 17].

## 3. I/O and Hardware Mapping
Instruction decoding for `INP` and `OUT` ports is handled via `N0`, `N1`, and `N2` pins[cite: 17].

### Output Ports
* **OUT 2:** LCD Command Register (Sends instructions like 0x38, 0x01 to the HD44780 controller)[cite: 17].
* **OUT 3:** Matrix Row Selector. The OS outputs immediate memory bytes to this port to pull specific rows low. The active-low row masks are `0x1E`, `0x1D`, `0x1B`, `0x17`, and `0x0F`, corresponding to a 5-row physical matrix (Rows 0 through 4)[cite: 16, 20].

### Input Ports
* **INP 3:** Matrix Column Reader. Read immediately after an `OUT 3` mask is applied to sense button closures on the active row[cite: 16, 20].
* **INP 4:** Hardware Watchdog Strobe. The OS polls this port continuously. If bits 6 and 7 are high (e.g., returning `0xFF`), the OS intentionally traps the CPU in an infinite loop to force a physical hardware reboot. Must return `0x00` to pacify the watchdog[cite: 16, 20].
* **INP 7:** Option Pins (J6 Connector). Reads `PGM1` through `PGM5`. Unpopulated resting state is `0xFF`[cite: 17].

### External Flags
* **EF1:** Global Matrix Sense Line. Routed to the keypad matrix; drops unconditionally to `0` the millisecond any button is held to wake the OS and initiate a scan[cite: 17, 20].
* **EF2:** Tied to a timer interrupt circuit (54.625 Hz). This timer tick forces the CPU to jump to the `20FD` routine. The OS evaluates the matrix scan strictly on the *rising edge* (`1`) of this signal[cite: 17, 20].
* **EF3:** Routed to `DATA-IN` on the SIO Connector[cite: 17].
* **EF4:** Tied to the CPU `CLEAR` line[cite: 17].

### Serial Protocol (Scoreboard Output)
* **Hardware Routing:** Bit-banged directly via the CPU's `Q` pin, which connects to `DATA-OUT`[cite: 17].
* **Transport:** 20 mA current-loop, asynchronous serial at 2400 baud, 8 data bits, no parity, 1 stop bit[cite: 17].
* **Payload Structure:** Continuous stream of two-byte frames[cite: 17].
    * **Byte 1 (Low):** MSB is 0. Lower 7 bits = LSBs of the 9-bit Data Payload[cite: 17].
    * **Byte 2 (High):** MSB is 1. Next 5 bits = Address (0-31). Lower 2 bits = MSBs of the 9-bit Data Payload[cite: 17].

## 4. User Interface (Java View)
The UI acts as a bridge to the hardware interfaces, visually representing two console models:
* **MM Model:** 15-button physical keypad layout with 2 indicator LEDs (Power, Main Timer). LCD is disabled/hidden.
* **MP Model:** 37-button physical keypad layout with 2x16 character LCD (mapped to HD44780 standard based on the Powertip PC-1602F using the ST7066U controller).
* **Handheld Switches:** UI toggles representing 3-button+LED and 1-button+LED external switch assemblies.
* **Configuration Menu:** Allows dynamic loading of .hex ROM files, toggling Option Pins (`PGM1`-`PGM5`), and swapping sport-specific button label overlays.
* **Keypad Matrix Mapping:** The physical hardware utilizes an 8 column x 5 row matrix. The OS drives a walking zero across 5 rows (OUT 3 masks 0x1E, 0x1D, 0x1B, 0x17, 0x0F mapping to Rows 0-4) and reads the active-low columns on INP 3.

## 5. Development Roadmap
* **Phase 1: Virtual CPU Core:** Implement 1802 registers (R0-RF, D, P, X, N, DF, IE, Q) and the fetch-decode-execute cycle. Verify via opcode unit tests.
* **Phase 2: Memory Bus & ROM Loader:** Implement the memory map and write a parser to load Intel HEX files into the virtual EEPROM space.
* **Phase 3: I/O & Output Protocol:** Bind the `Q` pin and `INP`/`OUT` instructions to Java interfaces. Route the 2400-baud serial output to a scrolling text debugger window.
* **Phase 4: Hardware Interfaces:** Emulate keypad matrix scanning via `EF1` and data bus strobes, map option pins to `INP 7`, and build internal LCD memory state.
* **Phase 5: Graphical UI:** Build Java visual representations of the MM/MP consoles, bind mouse clicks to virtual switch closures, and implement dynamic keypad labeling.