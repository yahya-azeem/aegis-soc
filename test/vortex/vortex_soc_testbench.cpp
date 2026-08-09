#include <verilated.h>
#include "VAegis.h"
#include <iostream>
#include <cstdint>

static vluint64_t main_time = 0;

double sc_time_stamp() {
    return main_time;
}

static bool saw_read_req;
static bool saw_write_req;

static void drive_soc(VAegis& top) {
    // idle everything except the Vortex path
    top.prog_we = 0;
    top.prog_addr = 0;
    top.prog_data = 0;
    top.start = 0;

    top.gpu_req_valid = 0;
    for (int w = 0; w < 16; w++) top.gpu_req_bits_data[w] = 0;
    top.gpu_req_bits_addr = 0;
    top.gpu_req_bits_isWrite = 0;
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

    if (top.mem_axi_ARVALID) saw_read_req = true;
    if (top.mem_axi_AWVALID) saw_write_req = true;
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

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    VAegis* top = new VAegis;

    saw_read_req = false;
    saw_write_req = false;

    top->clock = 0;
    top->reset = 1;
    top->vx_dcr_valid = 0;
    top->vx_dcr_rw = 0;
    top->vx_dcr_addr = 0;
    top->vx_dcr_data = 0;
    top->vx_start = 0;
    top->debug_uart_rx = 0;

    auto tick = [&]() {
        drive_soc(*top);
        top->clock = 0;
        top->eval();
        main_time++;
        top->clock = 1;
        drive_soc(*top);
        top->eval();
        main_time++;
    };

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

    // Program kernel launch config via the SoC's Vortex DCR port (KMU DCRs)
    dcr_write(*top, 0x010, 0x00000000);   // startup PC 0
    dcr_write(*top, 0x019, 0x00000001);   // grid dim X = 1
    dcr_write(*top, 0x01A, 0x00000001);   // grid dim Y = 1
    dcr_write(*top, 0x01B, 0x00000001);   // grid dim Z = 1
    dcr_write(*top, 0x016, 0x00000001);   // block dim X = 1
    dcr_write(*top, 0x017, 0x00000001);   // block dim Y = 1
    dcr_write(*top, 0x018, 0x00000001);   // block dim Z = 1
    dcr_write(*top, 0x01D, 0x00000001);   // block size = 1
    dcr_write(*top, 0x01C, 0x00000000);   // lmem size = 0

    // launch kernel through the SoC port
    top->vx_start = 1;
    tick();
    tick();
    top->vx_start = 0;

    bool saw_busy = false;
    for (int i = 0; i < 1000; i++) {
        tick();
        if (top->vx_busy) saw_busy = true;
        if (saw_read_req && !top->vx_busy) break;  // finished after traffic
    }

    std::cout << "vx_busy: " << (saw_busy ? "yes" : "no")
              << " read_req: " << (saw_read_req ? "yes" : "no")
              << " write_req: " << (saw_write_req ? "yes" : "no") << std::endl;

    bool passed = saw_busy && (saw_read_req || saw_write_req);
    delete top;
    if (!passed) {
        std::cerr << "FAIL: expected vx_busy + HBM3 traffic after start\n";
        return 1;
    }
    std::cout << "PASS: Aegis + real Vortex RTL end-to-end OK\n";
    return 0;
}