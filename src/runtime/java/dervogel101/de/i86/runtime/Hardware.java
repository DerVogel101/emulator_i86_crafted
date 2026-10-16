package dervogel101.de.i86.runtime;

/** The installed-device rules shared by the Minecraft host and Swing launcher. */
public enum Hardware {
    CONTROLLER("mainframe_controller", "Mainframe Controller", 1, true),
    CLOCK("atomic_clock", "Atomic Clock", 1, false),
    INTERRUPTS("interrupt_controller", "Interrupt Controller", 1, false),
    TERMINAL("access_terminal", "Access Terminal", 1, false),
    GPU("gpu_array", "GPU Array", 4, false),
    GPIO("gpio_controller", "GPIO Controller", 4, true),
    DISK("harddisk_array", "Harddisk Array", 1, false),
    SHARED_MEMORY("ender_tunnel_array", "Ender Tunnel Array", 1, false),
    NVRAM("integrated_storage", "Integrated Storage", 1, false),
    POWER("power_supply", "Power Supply", 1, true),
    RESET("reset_port", "Reset Port", 1, true),
    NMI("nmi_port", "NMI Port", 1, true),
    TEST("test_port", "TEST Port", 1, true);

    public final String id;
    public final String displayName;
    public final int maximum;
    public final boolean required;

    Hardware(String id, String displayName, int maximum, boolean required) {
        this.id = id;
        this.displayName = displayName;
        this.maximum = maximum;
        this.required = required;
    }
}
