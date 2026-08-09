#include <verilated.h>
#include "VVortexShell.h"
#include <iostream>
#include <cstdint>
#include <cstring>

static vluint64_t main_time = 0;

double sc_time_stamp() {
    return main_time;
}

static bool saw_read_req;
static bool saw_write_req;
static bool rsp_pending_read;
static bool rsp_pending_write;
static uint8_t rsp_rid;

static void drive_axi_slave(VVortexShell& top) {
    // ready signals: always accept new requests
    top.m_axi_awready = 1;
    top.m_axi_wready = 1;
    top.m_axi_arready = 1;
    top.m_axi_bvalid = 0;
    top.m_axi_rvalid = 0;

    if (top.m_axi_awvalid) {
        saw_write_req = true;
        rsp_pending_write = true;
    }
    if (top.m_axi_arvalid) {
        saw_read_req = true;
        rsp_pending_read = true;
        rsp_rid = top.m_axi_arid;
    }

    if (rsp_pending_read) {
        top.m_axi_rvalid = 1;
        top.m_axi_rlast = 1;
        top.m_axi_rresp = 0;
        top.m_axi_rid = rsp_rid;
        for (int w = 0; w < 16; w++) top.m_axi_rdata[w] = 0;
        if (top.m_axi_rready) {
            rsp_pending_read = false;
        }
    }

    if (rsp_pending_write) {
        top.m_axi_bvalid = 1;
        top.m_axi_bresp = 0;
        top.m_axi_bid = 0;
        top.m_axi_rlast = 0;
        if (top.m_axi_bready) {
            rsp_pending_write = false;
        }
    }
}

static void dcr_write(VVortexShell& top, uint32_t addr, uint32_t data) {
    top.dcr_req_valid = 1;
    top.dcr_req_rw = 1;
    top.dcr_req_addr = addr;
    top.dcr_req_data = data;
    top.clk = 0;
    top.eval();
    main_time++;
    top.clk = 1;
    top.eval();
    main_time++;
    top.dcr_req_valid = 0;
    top.eval();
    main_time++;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    VVortexShell* top = new VVortexShell;

    saw_read_req = false;
    saw_write_req = false;
    rsp_pending_read = false;
    rsp_pending_write = false;
    rsp_rid = 0;

    top->clk = 0;
    top->reset = 1;
    top->start = 0;
    top->dcr_req_valid = 0;
    top->dcr_req_rw = 0;
    top->dcr_req_addr = 0;
    top->dcr_req_data = 0;
    top->m_axi_awready = 0;
    top->m_axi_wready = 0;
    top->m_axi_bvalid = 0;
    top->m_axi_bid = 0;
    top->m_axi_bresp = 0;
    top->m_axi_arready = 0;
    top->m_axi_rvalid = 0;
    for (int w = 0; w < 16; w++) top->m_axi_rdata[w] = 0;
    top->m_axi_rlast = 0;
    top->m_axi_rid = 0;
    top->m_axi_rresp = 0;

    auto tick = [&]() {
        drive_axi_slave(*top);
        top->clk = 0;
        top->eval();
        main_time++;
        top->clk = 1;
        drive_axi_slave(*top);
        top->eval();
        main_time++;
    };

    // reset period
    for (int i = 0; i < 16; i++) tick();

    if (top->busy != 0) {
        std::cerr << "FAIL: busy was not 0 after reset\n";
        delete top;
        return 1;
    }
    top->reset = 0;
    tick();

    // idle: vortex must not have hit memory without start
    for (int i = 0; i < 16; i++) tick();

    // Program kernel launch config via DCR
    dcr_write(*top, 0x010, 0x00000000);   // startup PC 0
    dcr_write(*top, 0x019, 0x00000001);   // grid dim X = 1 (VX_DCR_KMU_GRID_DIM_X)
    dcr_write(*top, 0x01A, 0x00000001);   // grid dim Y = 1
    dcr_write(*top, 0x01B, 0x00000001);   // grid dim Z = 1
    dcr_write(*top, 0x016, 0x00000001);   // block dim X = 1 (VX_DCR_KMU_BLOCK_DIM_X)
    dcr_write(*top, 0x017, 0x00000001);   // block dim Y = 1
    dcr_write(*top, 0x018, 0x00000001);   // block dim Z = 1
    dcr_write(*top, 0x01D, 0x00000001);   // block size = 1 (VX_DCR_KMU_BLOCK_SIZE)
    dcr_write(*top, 0x01C, 0x00000000);   // lmem size = 0

    // launch a kernel; empty program in zeroed RAM = garbage but no hang
    top->start = 1;
    tick();
    tick();
    top->start = 0;

    bool saw_busy = false;
    bool saw_busy_initial = false;
    for (int i = 0; i < 500; i++) {
        tick();
        if (top->busy) {
            if (!saw_busy) saw_busy_initial = true;
            saw_busy = true;
        }
    }

    std::cout << "busy: " << (saw_busy ? "yes" : "no")
              << " read_req: " << (saw_read_req ? "yes" : "no")
              << " write_req: " << (saw_write_req ? "yes" : "no") << std::endl;

    bool passed = saw_busy && (saw_read_req || saw_write_req);
    delete top;
    if (!passed) {
        std::cerr << "FAIL: expected busy + bus activity after start\n";
        return 1;
    }
    std::cout << "PASS: VortexShell smoke OK\n";
    return 0;
}