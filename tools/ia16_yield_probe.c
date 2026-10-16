/* Representative compiler output; private instructions appear only in these two explicit helpers. */
typedef unsigned short u16;
typedef unsigned long u32;

u16 integer_math(u16 a, u16 b) { return (a * 17u) / (b | 1u); }
u32 wide_math(u32 a, u32 b) { return (a ^ (b << 3)) / (b | 1ul); }

void copy_bytes(volatile unsigned char *out, const unsigned char *in, u16 count) {
    while (count--) *out++ = *in++;
}

u16 read_port(u16 port) {
    u16 value;
    __asm__ volatile ("inw %1, %0" : "=a"(value) : "d"(port));
    return value;
}

void write_port(u16 port, u16 value) {
    __asm__ volatile ("outw %0, %1" : : "a"(value), "d"(port));
}

float float_add(float a, float b) { return a + b; }
double float_divide(double a, double b) { return a / b; }
void compiler_trap(void) { __builtin_trap(); }

void yield_batch(void) { __asm__ volatile (".byte 0x0f, 0x00" : : : "memory"); }
void yield_tick(void) { __asm__ volatile (".byte 0x0f, 0x01" : : : "memory"); }
