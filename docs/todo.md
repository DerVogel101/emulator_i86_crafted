# Mod feature list

This file lists the planned blocks and features. Confirmed behavior is specified in
[mod_behavior.md](mod_behavior.md). Decisions still needed are listed in
[open_questions.md](open_questions.md).

CPU work is specified in [cpu_scheduling.md](cpu_scheduling.md), with the planned
private yield extension in [yield_instruction.md](yield_instruction.md).

1. Mainframe multiblock
   1. Components:
      1. 1 Mainframe Controller
         1. Can be used to access the debugger gui
         2. Gets assigned an ID and friendly name, which is used to identify the mainframe in the debugger gui and ports
      2. 1 Atomic Clock         
         1. Timers and mem Clocks
         2. Optional
      3. 1 Interrupt Controller
         1. IRQ Queue, needed for any hardware interrupts
         2. GUI for managing Devices
         3. Optional
      4. 1 Access Terminal      
         1. Output Terminal/Log
         2. Display Exception from the CPU
         3. Same extended ASCII/Windows‑1252 support as Signs
         4. Optional
      5. 4 GPU Arrays           
         1. per 32 Signs ports
         2. Optional
      6. 4 GPIO Controller                 
         1. 32 Redstone ports each, min 1
      7. 1 Harddisk Array       
         1. IO Persistent Storage
         2. Optional
      8. 1 Ender Tunnel Array   
         1. Shared Memory
         2. Optional
      9. 1 Integrated Storage
         1. NVRAM, bound to Controller
         2. Optional
      10. 1 Power Supply                    
          1. mainframe stays on, as long its redstone powered
          2. causes Power Cycle
      11. 1 Reset Port
          1. triggers reset, with redstone pulse
      12. 1 NMI Port
          1. triggers NMI, with redstone pulse
      13. 1 TEST Port
          1. when powered with redstone, sets test pin high
      14. Mainframe Super Structure
          1. filler block
   2. Size: 7x3x1 Wall
      1. Needs to be placed in one Chunk
   3. The components have manual buttons in their gui for triggering
      1. NMI, RESET, powersupply

2. ROM Burner Block
   1. Writes an rom image to an ROM Chip, using a rom image file from the filesystem of the player
   2. The item saves an reference to the actual bin file in the world folder
   3. Needs to be inserted into the controller to be used
3. Debugger/Emulator
   1. Uses the Launcher GUI of the emulator
   2. CPU State gets only transmitted to the players GUI when the cpu is paused from the GUI or stepping, not during run for network/performance
   3. Features like loading ROM images into the cpu is disabled since the mod owns them
   4. Multiple CPU viewing is supported
4. External Port Blocks
   1. In World IO Ports
   2. Needs short name, for irq block gui
   3. associated with mainframe via id+name selector
   4. variant for redstone and signs
   5. Redstone Var
      1. Set to either input or output
      2. Allows enabling interrupts via gui and irq interface
      3. Interrupt modes are as following
         1. Binary
            1. Senses Change between on and off
            2. HIGH, senses 0->1 transition
            3. LOW, senses 1->0 transition
            4. BOTH, senses all transitions
         2. Analog
            1. Sends interrupt on all redstone power levels
   6. Ports have direction, like dispenser, the way its looking at is its interaction side
   7. Sign Var
      1. Interacts with the Sign the port points at
      2. allows enabling interrupts for clicks via gui and irq interface
   8. When the Ports are not loaded, they will not be able to send interrupts or interact with the world, but the mainframe will still be able to read their state last written into the io, the mainframe needs to remember them, and display their coordinates
5. Clock Crystal
   1. Needs to be inserted into the Mainframe controller and sets the instruction steps per tick
   2. These need 3 Tiers, configurable
      1. Tier 1: 1000
      2. Tier 2: 5000
      3. Tier 3: 10000
