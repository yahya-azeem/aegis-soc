#include <verilated.h>
#include "VAegis.h"
#include <iostream>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <vector>
#include <string>
#include <verilated_vcd_c.h>

static vluint64_t main_time = 0;
static VerilatedVcdC* g_tfp = nullptr;

double sc_time_stamp() {
    return main_time;
}

static bool saw_read_req;
static bool saw_write_req;
static bool match_magic_store;

// CPU shared-memory phase observability (via the HBM3 mirror)
static bool cpu_phase;
static bool cpu_saw_rd;
static bool cpu_saw_wr;

// shared gpu-lane transaction request, held until it completes
static bool mem_pending;
static uint64_t mem_addr;
static uint32_t mem_data[16];
static bool mem_is_write;

static void drive_soc(VAegis& top) {
    if (g_tfp) g_tfp->dump(main_time);
    // idle everything except the Vortex path
    top.prog_we = 0;
    top.prog_addr = 0;
    top.prog_data = 0;
    top.start = 0;

    // gpu lane = shared-memory access from the testbench (L2 -> HBM3 stack)
    top.gpu_req_valid = mem_pending ? 1 : 0;
    for (int w = 0; w < 16; w++) top.gpu_req_bits_data[w] = mem_data[w];
    top.gpu_req_bits_addr = mem_addr;
    top.gpu_req_bits_isWrite = mem_is_write ? 1 : 0;
    top.gpu_req_bits_size = 0;
    top.gpu_resp_ready = 1;

    top.simt_start = 0;
    top.simt_baseX = 0;
    top.simt_baseZ = 0;
    top.simt_baseY = 0;
    top.simt_nLines = 0;

    top.gemm_start = 0;
    top.gemm_base = 0;

    // HBM3 mirror is purely observability; holds never depend on it.
    top.mem_axi_AWREADY = 0;
    top.mem_axi_WREADY = 0;
    top.mem_axi_BVALID = 0;
    top.mem_axi_BRESP = 0;
    top.mem_axi_BID = 0;
    top.mem_axi_ARREADY = 0;
    top.mem_axi_RVALID = 0;
    top.mem_axi_RLAST = 0;
    top.mem_axi_RRESP = 0;
    top.mem_axi_RID = 0;
    for (int w = 0; w < 16; w++) top.mem_axi_RDATA[w] = 0;

    if (top.mem_axi_ARVALID) {
        saw_read_req = true;
        std::cout << "AR: addr=0x" << std::hex << top.mem_axi_ARADDR << std::dec << std::endl;
        if (cpu_phase && top.mem_axi_ARADDR == 0x00010000ULL) cpu_saw_rd = true;
    }
    if (top.mem_axi_AWVALID) {
        saw_write_req = true;
        std::cout << "AW: addr=0x" << std::hex << top.mem_axi_AWADDR
                  << " data[0]=0x" << top.mem_axi_WDATA[0] << std::dec << std::endl;
        if (top.mem_axi_AWADDR == 0x00010000ULL && top.mem_axi_WDATA[0] == 0xa5a5u)
            match_magic_store = true;
        if (cpu_phase && top.mem_axi_AWADDR == 0x00010040ULL) cpu_saw_wr = true;
    }
}

static void dcr_write(VAegis& top, uint32_t addr, uint32_t data) {
    top.vx_dcr_valid = 1;
    top.vx_dcr_rw = 1;
    top.vx_dcr_addr = addr;
    top.vx_dcr_data = data;
    top.clock = 0;
    top.eval();
    main_time++;
    top.clock = 1;
    top.eval();
    main_time++;
    top.vx_dcr_valid = 0;
    top.eval();
    main_time++;
}

static bool dcr_read(VAegis& top, uint32_t addr, uint32_t& data) {
    top.vx_dcr_valid = 1;
    top.vx_dcr_rw = 0;
    top.vx_dcr_addr = addr;
    top.vx_dcr_data = 0;
    top.clock = 0;
    top.eval();
    main_time++;
    top.clock = 1;
    top.eval();
    main_time++;
    top.vx_dcr_valid = 0;
    top.eval();
    main_time++;
    for (int i = 0; i < 8; i++) {
        top.clock = 0;
        top.eval();
        main_time++;
        top.clock = 1;
        top.eval();
        main_time++;
        if (top.vx_dcr_rsp_valid) {
            data = top.vx_dcr_rsp_data;
            return true;
        }
    }
    return false;
}

static void step(VAegis& top) {
    drive_soc(top);
    top.clock = 0;
    top.eval();
    main_time++;
    top.clock = 1;
    drive_soc(top);
    top.eval();
    main_time++;
}

// clock toggle without drive_soc, so prog_we/start pulses can be held across
// a real evaluate cycle (drive_soc would clear them otherwise)
static void tick_raw(VAegis& top) {
    if (g_tfp) g_tfp->dump(main_time);
    top.clock = 0;
    top.eval();
    main_time++;
    top.clock = 1;
    top.eval();
    main_time++;
}

// write one instruction word into the CPU's instruction memory
static void load_prog_word(VAegis& top, uint32_t addr, uint32_t data) {
    top.prog_we = 1;
    top.prog_addr = addr;
    top.prog_data = data;
    tick_raw(top);
    top.prog_we = 0;
    tick_raw(top);
}

// write a full 512-bit line through the gpu lane into shared HBM3
static void mem_write_line(VAegis& top, uint64_t addr, const uint32_t data[16]) {
    mem_pending = true;
    mem_addr = addr;
    mem_is_write = true;
    for (int w = 0; w < 16; w++) mem_data[w] = data[w];
    bool done = false;
    for (int i = 0; i < 4000; i++) {
        step(top);
        if (top.gpu_resp_valid) { done = true; break; }
    }
    mem_pending = false;
    step(top);
    if (!done) std::cerr << "WARN: mem_write_line(" << std::hex << addr << ") no resp\n";
}

// read a full 512-bit line through the gpu lane from shared HBM3
static void mem_read_line(VAegis& top, uint64_t addr, uint32_t out[16]) {
    mem_pending = true;
    mem_addr = addr;
    mem_is_write = false;
    for (int w = 0; w < 16; w++) mem_data[w] = 0;
    bool done = false;
    for (int i = 0; i < 4000; i++) {
        step(top);
        if (top.gpu_resp_valid) {
            for (int w = 0; w < 16; w++) out[w] = top.gpu_resp_bits[w];
            done = true;
            break;
        }
    }
    mem_pending = false;
    step(top);
    if (!done) std::cerr << "WARN: mem_read_line(" << std::hex << addr << ") no resp\n";
}

// Program the Vortex KMU launch registers for a single-thread kernel run at pc.
static void program_vx_launch(VAegis& top, uint32_t pc) {
    dcr_write(top, 0x010, pc);   // startup PC
    dcr_write(top, 0x012, pc);   // kernel entry PC
    dcr_write(top, 0x019, 1);    // grid dim X
    dcr_write(top, 0x01A, 1);    // grid dim Y
    dcr_write(top, 0x01B, 1);    // grid dim Z
    dcr_write(top, 0x016, 1);    // block dim X
    dcr_write(top, 0x017, 1);    // block dim Y
    dcr_write(top, 0x018, 1);    // block dim Z
    dcr_write(top, 0x01D, 1);    // block size = 1 thread
    dcr_write(top, 0x01C, 0);    // lmem size
    dcr_write(top, 0x021, 1);    // cluster dim X
    dcr_write(top, 0x022, 1);    // cluster dim Y
    dcr_write(top, 0x023, 1);    // cluster dim Z
}

// Raytracer phase: seed rt_balls.bin at VMA 0x100, run it on the real Vortex
// RTL, then read the framebuffer back from 0x10000 and compare with the
// host-side golden image (per-channel tolerance for float noise).
// Enabled with AEGIS_VX_RAYTRACE=1.
static bool run_raytracer_phase(VAegis& top) {
    const char* bin_path = std::getenv("AEGIS_VX_RT_BIN");
    const char* gold_path = std::getenv("AEGIS_VX_RT_GOLDEN");
    if (!bin_path) bin_path = "rt_balls.bin";
    if (!gold_path) gold_path = "rt_balls_golden.bin";
    const int rt_w = std::getenv("AEGIS_VX_RT_W") ? std::atoi(std::getenv("AEGIS_VX_RT_W")) : 20;
    const int rt_h = std::getenv("AEGIS_VX_RT_H") ? std::atoi(std::getenv("AEGIS_VX_RT_H")) : 20;
    const vluint64_t timeout = std::getenv("AEGIS_VX_RT_TIMEOUT")
                                   ? std::strtoull(std::getenv("AEGIS_VX_RT_TIMEOUT"), nullptr, 10)
                                   : 4000000ULL;

    std::cout << "\n== raytracer phase ==\n";
    std::vector<uint8_t> bin;
    {   std::ifstream f(bin_path, std::ios::binary);
        if (!f.good()) { std::cerr << "FAIL(rt): cannot open " << bin_path << "\n"; return false; }
        bin.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    }

    // seed the kernel image into shared HBM3 at VMA 0x100 (64B lines)
    const uint64_t img_base = 0x00000100ULL;
    for (size_t off = 0; off < bin.size(); off += 64) {
        uint32_t line[16] = {0};
        for (int w = 0; w < 16 && off + 4 * w < bin.size(); w++)
            line[w] = (uint32_t(bin[off + 4 * w]) << 0) | (uint32_t(bin[off + 4 * w + 1]) << 8) |
                      (uint32_t(bin[off + 4 * w + 2]) << 16) | (uint32_t(bin[off + 4 * w + 3]) << 24);
        mem_write_line(top, img_base + off, line);
    }
    std::cout << "seeded kernel: " << bin.size() << " bytes at 0x100\n";

    program_vx_launch(top, 0x00000100u);
    top.vx_start = 1;
    tick_raw(top);
    top.vx_start = 0;
    tick_raw(top);

    bool saw_busy = false, busy_dropped = false, was_busy = false;
    uint64_t fb_aw = 0; // DRAM writes landing in the framebuffer range = pixels stored
    const uint64_t fb_lo = 0x00002000ULL, fb_hi = fb_lo + uint64_t(rt_w) * rt_h * 4;
    for (vluint64_t i = 0; i < timeout; i++) {
        step(top);
        if (top.mem_axi_AWVALID && (uint64_t)top.mem_axi_AWADDR >= fb_lo && (uint64_t)top.mem_axi_AWADDR < fb_hi)
            fb_aw++;
        if (top.vx_busy) { saw_busy = true; was_busy = true; }
        else if (was_busy) { busy_dropped = true; was_busy = false; break; }
        if ((i % 100000ULL) == 0)
            std::cout << "rt: step=" << i << " busy=" << (top.vx_busy ? 1 : 0)
                      << " fb_writes=" << (unsigned long)fb_aw << " st=" << (unsigned long)(saw_busy ? 1 : 0) << "\n";
    }
    if (!busy_dropped) {
        std::cerr << "FAIL(rt): kernel did not retire within " << timeout << " steps\n";
        return false;
    }
    std::cout << "rt kernel retired (saw_busy=" << saw_busy << "); flushing dcache\n";

    {   uint32_t unused;
        if (!dcr_read(top, 0x000, unused)) std::cout << "rt flush: no rsp seen\n";
    }
    for (int i = 0; i < 2000; i++) step(top); // let the stack absorb the evictions

    // read back the framebuffer from 0x2000 and compare against the golden
    std::ifstream fg(gold_path, std::ios::binary);
    if (!fg.good()) { std::cerr << "FAIL(rt): cannot open " << gold_path << "\n"; return false; }
    std::vector<uint8_t> golden((std::istreambuf_iterator<char>(fg)), std::istreambuf_iterator<char>());

    const uint64_t fb_base = 0x00002000ULL;
    const size_t fb_size = size_t(rt_w) * rt_h * 4;
    if (golden.size() < fb_size) { std::cerr << "FAIL(rt): golden too small\n"; return false; }

    size_t exact = 0, within = 0, worst = 0, worst_i = 0;
    uint64_t sum_err = 0;
    for (size_t off = 0; off < fb_size; off += 64) {
        uint32_t line[16] = {0};
        mem_read_line(top, fb_base + off, line);
        size_t nwords = (fb_size - off) / 4;
        if (nwords > 16) nwords = 16;
        for (size_t w = 0; w < nwords; w++) {
            uint32_t got = line[w];
            size_t b0 = off + 4 * w;
            uint32_t exp = (uint32_t(golden[b0]) << 0) | (uint32_t(golden[b0 + 1]) << 8) |
                           (uint32_t(golden[b0 + 2]) << 16) | (uint32_t(golden[b0 + 3]) << 24);
            if (got == exp) exact++;
            if (off == 0 && w == 0)
                std::cout << "rt px0: got=0x" << std::hex << got << " exp=0x" << exp << std::dec << "\n";
            size_t dw = 0;
            for (int ch = 0; ch < 3; ch++) {
                int cg = (got >> (8 * ch)) & 0xFF;
                int ce = (exp >> (8 * ch)) & 0xFF;
                size_t d = cg > ce ? (size_t)(cg - ce) : (size_t)(ce - cg);
                sum_err += d;
                if (d > dw) dw = d;
            }
            if (dw <= 4) within++;
            if (dw > worst) { worst = dw; worst_i = w; }
        }
    }
    size_t npix = fb_size / 4;
    bool rt_ok = (within == npix);
    std::cout << "rt framebuffer: " << npix << " pixels, exact=" << exact
              << ", within tol(4)=" << within << ", worst_ch_delta=" << worst << " @px " << worst_i
              << ", mean_ch_err=" << (sum_err / double(npix * 3))
              << (rt_ok ? " -> PASS" : " -> FAIL") << "\n";
    return rt_ok;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    VAegis* top = new VAegis;

    VerilatedVcdC* tfp = nullptr;
    const char* trace_path = std::getenv("AEGIS_TRACE");
    if (trace_path) {
        tfp = new VerilatedVcdC;
        top->trace(tfp, 99);
        tfp->open(trace_path);
        g_tfp = tfp;
    }

    if (tfp) tfp->dump(main_time);

    saw_read_req = false;
    saw_write_req = false;
    match_magic_store = false;
    cpu_phase = false;
    cpu_saw_rd = false;
    cpu_saw_wr = false;
    mem_pending = false;
    mem_addr = 0;
    mem_is_write = false;
    for (int w = 0; w < 16; w++) mem_data[w] = 0;

    top->clock = 0;
    top->reset = 1;
    top->vx_dcr_valid = 0;
    top->vx_dcr_rw = 0;
    top->vx_dcr_addr = 0;
    top->vx_dcr_data = 0;
    top->vx_start = 0;
    top->debug_uart_rx = 0;

    auto tick = [&]() { step(*top); };

    // reset period
    for (int i = 0; i < 16; i++) tick();

    if (top->vx_busy != 0) {
        std::cerr << "FAIL: vx_busy was not 0 after reset\n";
        delete top;
        return 1;
    }
    top->reset = 0;
    tick();

    // idle: no Vortex traffic without start
    for (int i = 0; i < 16; i++) tick();

    // Seed the kernel program into shared HBM3 at VMA 0x100 via the gpu lane.
    // lui t0,0x10 ; lui t1,0xA ; addi t1,t1,0x5A5 ; sw t1,0(t0) ; wsync ; tmc x0
    uint32_t kernel[16] = {
        0x000102b7, 0x0000a337, 0x5a530313, 0x0062a023,
        0x0000700b, 0x0000000b, 0x00000013, 0x00000013,
        0x00000013, 0x00000013, 0x00000013, 0x00000013,
        0x00000013, 0x00000013, 0x00000013, 0x00000013,
    };
    mem_write_line(*top, 0x00000100ULL, kernel);

    // verify the seed landed in shared HBM3
    {
        uint32_t check[16] = {0};
        mem_read_line(*top, 0x00000100ULL, check);
        std::cout << "seed[0x100] word0 = 0x" << std::hex << check[0] << std::dec << std::endl;
    }

    // Program kernel launch config via the SoC's Vortex DCR port (KMU DCRs).
    // startup PC = 0x100 (VMA, non-zero)
    program_vx_launch(*top, 0x00000100u);

    // launch kernel with a single-cycle start pulse
    top->vx_start = 1;
    tick();
    top->vx_start = 0;
    tick();

    bool saw_busy = false;
    bool busy_dropped = false;
    bool last_busy = false;
    for (int i = 0; i < 3000; i++) {
        tick();
        if (top->vx_busy) {
            saw_busy = true;
            last_busy = true;
        } else if (last_busy) {
            busy_dropped = true;
            last_busy = false;
        }
    }

    // After the kernel retires, flush the write-back LLC so the dirty store
    // line is evicted out onto the acc lane into shared HBM3. Flush is a DCR
    // read at VX_DCR_BASE_CACHE_FLUSH (0x000).
    if (busy_dropped) {
        uint32_t unused;
        std::cout << "busy dropped; flushing dcache\n";
        if (dcr_read(*top, 0x000, unused)) {
            std::cout << "flush done\n";
        } else {
            std::cout << "flush: no rsp seen\n";
        }
        for (int i = 0; i < 500; i++) tick();
    }

    // Read back the store from shared HBM3 through the gpu lane and verify.
    uint32_t line[16] = {0};
    mem_read_line(*top, 0x00010000ULL, line);
    bool store_ok = (line[0] == 0xa5a5u);
    std::cout << "readback[0x10000] = 0x" << std::hex << line[0] << std::dec << std::endl;

    // ---- CPU: hand-assembled loop program through the CPU port ----
    // Seed a 4-word vector into shared HBM3 at 0x10000 via the gpu lane, then
    // run a RV32I loop program on the core that sums the vector with a branch
    // and stores the result to 0x10040 -- exercising loads, ALU, branches/loops
    // and a store against the real shared stack.
    cpu_phase = true;
    cpu_saw_rd = false;
    cpu_saw_wr = false;

    uint32_t cpu_seed[16] = {0};
    cpu_seed[0] = 10u; cpu_seed[1] = 20u; cpu_seed[2] = 30u; cpu_seed[3] = 40u;
    mem_write_line(*top, 0x00010000ULL, cpu_seed);

    //   lui  s0,0x10      ; s0 = 0x10000 (ptr)
    //   addi s1,x0,4      ; s1 = 4 (count)
    //   xor  s2,s2,s2     ; s2 = 0 (sum)
    // loop:
    //   lw   t0,0(s0)     ; t0 = mem[ptr]
    //   add  s2,s2,t0     ; sum += t0
    //   addi s0,s0,4      ; ptr += 4
    //   addi s1,s1,-1     ; count -= 1
    //   bne  s1,x0,loop   ; while (count) goto loop
    //   lui  t1,0x10      ; t1 = 0x10000
    //   addi t1,t1,0x40   ; t1 = 0x10040
    //   sw   s2,0(t1)     ; mem[0x10040] = sum
    //   halt
    uint32_t prog[12] = {
        0x00010437, 0x00400493, 0x01294933, 0x00042283,
        0x00590933, 0x00440413, 0xfff48493, 0xfe0498e3,
        0x00010337, 0x04030313, 0x01232023, 0xffffffff,
    };
    for (int w = 0; w < 12; w++) load_prog_word(*top, w, prog[w]);

    top->start = 1;
    tick_raw(*top);
    top->start = 0;
    tick_raw(*top);

    bool cpu_halted = false;
    for (int i = 0; i < 4000 && !cpu_halted; i++) {
        step(*top);
        if (top->halt) cpu_halted = true;
    }
    uint32_t cpu_s0 = top->regs_8;
    uint32_t cpu_s1 = top->regs_9;
    uint32_t cpu_s2 = top->regs_18;

    // The L3 is write-back, so the CPU's dirty store is evicted to shared
    // HBM3 by the flush-on-halt when the core halts. Keep the mirror monitor
    // armed and wait for the eviction writeback at 0x10040 before reading the
    // result back through the gpu lane.
    bool cpu_flushed = false;
    for (int i = 0; i < 600 && !cpu_flushed; i++) {
        step(*top);
        if (cpu_saw_wr) cpu_flushed = true;
    }
    for (int i = 0; i < 200; i++) step(*top); // let the stack absorb the write
    cpu_phase = false;

    uint32_t cpu_line[16] = {0};
    mem_read_line(*top, 0x00010040ULL, cpu_line);
    bool cpu_ok = cpu_halted && cpu_saw_rd && cpu_saw_wr
                  && (cpu_s2 == 100u) && (cpu_line[0] == 100u) && (cpu_s1 == 0u);
    std::cout << "cpu_halted: " << (cpu_halted ? "yes" : "no")
              << " cpu_seen_mem: " << (cpu_saw_rd ? "rd" : "-") << "/"
              << (cpu_saw_wr ? "wr" : "-")
              << " sum(s2)=0x" << std::hex << cpu_s2 << std::dec
              << " (" << cpu_s2 << ")"
              << " count(s1)=" << cpu_s1
              << " ptr(s0)=0x" << std::hex << cpu_s0 << std::dec
              << " cpu_store[0x10040]=0x" << std::hex << cpu_line[0] << std::dec
              << " (" << cpu_line[0] << ")" << std::endl;

    std::cout << "vx_busy: " << (saw_busy ? "yes" : "no")
              << " busy_dropped: " << (busy_dropped ? "yes" : "no")
              << " read_req: " << (saw_read_req ? "yes" : "no")
              << " write_req: " << (saw_write_req ? "yes" : "no")
              << " mirror_magic: " << (match_magic_store ? "yes" : "no")
              << " shared_mem_store: " << (store_ok ? "yes" : "no") << std::endl;

    bool rt_ok = true;
    if (std::getenv("AEGIS_VX_RAYTRACE"))
        rt_ok = run_raytracer_phase(*top);

    bool passed = saw_busy && busy_dropped && store_ok && cpu_ok && rt_ok;
    if (tfp) { tfp->flush(); tfp->close(); }
    delete top;
    if (!passed) {
        std::cerr << "FAIL: expected vx_busy + shared HBM3 magic store + CPU loop sum in shared HBM3";
        if (!rt_ok) std::cerr << " + raytracer framebuffer";
        std::cerr << "\n";
        return 1;
    }
    std::cout << "PASS: Aegis + real Vortex RTL end-to-end, kernel ran from shared HBM3, CPU loop summed shared HBM3";
    if (std::getenv("AEGIS_VX_RAYTRACE")) std::cout << ", raytracer framebuffer verified vs golden";
    std::cout << "\n";
    return 0;
}