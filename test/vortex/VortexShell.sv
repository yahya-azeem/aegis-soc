// VortexShell: flat-pin wrapper around the real Vortex_axi GPGPU so a simple
// top-level can instantiate the actual Vortex RTL without duplicating the AXI
// bank-array port types. With AXI_NUM_BANKS=1 the bank array collapses to a
// single element, which we re-expose as plain packed pins.
//
// It exposes:
//   m_axi_*      - AXI4 master (Vortex is the memory requester)
//   dcr_*        - 32-bit device-control registers (12-bit addr)
//   start/busy   - kernel launch + global busy

`include "VX_define.vh"

module VortexShell (
    input  wire                  clk,
    input  wire                  reset,

    // AXI write request channel
    output wire                  m_axi_awvalid,
    input  wire                  m_axi_awready,
    output wire [31:0]           m_axi_awaddr,
    output wire [ 7:0]           m_axi_awid,
    output wire [ 7:0]           m_axi_awlen,
    output wire [ 2:0]           m_axi_awsize,
    output wire [ 1:0]           m_axi_awburst,
    output wire [ 1:0]           m_axi_awlock,
    output wire [ 3:0]           m_axi_awcache,
    output wire [ 2:0]           m_axi_awprot,
    output wire [ 3:0]           m_axi_awqos,
    output wire [ 3:0]           m_axi_awregion,

    // AXI write data channel
    output wire                  m_axi_wvalid,
    input  wire                  m_axi_wready,
    output wire [511:0]          m_axi_wdata,
    output wire [63:0]           m_axi_wstrb,
    output wire                  m_axi_wlast,

    // AXI write response channel
    input  wire                  m_axi_bvalid,
    output wire                  m_axi_bready,
    input  wire [ 7:0]           m_axi_bid,
    input  wire [ 1:0]           m_axi_bresp,

    // AXI read request channel
    output wire                  m_axi_arvalid,
    input  wire                  m_axi_arready,
    output wire [31:0]           m_axi_araddr,
    output wire [ 7:0]           m_axi_arid,
    output wire [ 7:0]           m_axi_arlen,
    output wire [ 2:0]           m_axi_arsize,
    output wire [ 1:0]           m_axi_arburst,
    output wire [ 1:0]           m_axi_arlock,
    output wire [ 3:0]           m_axi_arcache,
    output wire [ 2:0]           m_axi_arprot,
    output wire [ 3:0]           m_axi_arqos,
    output wire [ 3:0]           m_axi_arregion,

    // AXI read response channel
    input  wire                  m_axi_rvalid,
    output wire                  m_axi_rready,
    input  wire [511:0]          m_axi_rdata,
    input  wire                  m_axi_rlast,
    input  wire [ 7:0]           m_axi_rid,
    input  wire [ 1:0]           m_axi_rresp,

    // DCR write request / read response
    input  wire                  dcr_req_valid,
    input  wire                  dcr_req_rw,
    input  wire [11:0]           dcr_req_addr,
    input  wire [31:0]           dcr_req_data,
    output wire                  dcr_rsp_valid,
    output wire [31:0]           dcr_rsp_data,

    // control / status
    input  wire                  start,
    output wire                  busy
);

    wire m_axi_awvalid_t [0:0];
    wire m_axi_awready_t [0:0];
    wire [31:0] m_axi_awaddr_t [0:0];
    wire [7:0]  m_axi_awid_t   [0:0];
    wire [7:0]  m_axi_awlen_t  [0:0];
    wire [2:0]  m_axi_awsize_t [0:0];
    wire [1:0]  m_axi_awburst_t[0:0];
    wire [1:0]  m_axi_awlock_t [0:0];
    wire [3:0]  m_axi_awcache_t[0:0];
    wire [2:0]  m_axi_awprot_t [0:0];
    wire [3:0]  m_axi_awqos_t  [0:0];
    wire [3:0]  m_axi_awregion_t[0:0];

    wire m_axi_wvalid_t [0:0];
    wire m_axi_wready_t [0:0];
    wire [511:0] m_axi_wdata_t [0:0];
    wire [63:0]  m_axi_wstrb_t [0:0];
    wire m_axi_wlast_t [0:0];

    wire m_axi_bvalid_t [0:0];
    wire m_axi_bready_t [0:0];
    wire [7:0] m_axi_bid_t   [0:0];
    wire [1:0] m_axi_bresp_t [0:0];

    wire m_axi_arvalid_t [0:0];
    wire m_axi_arready_t [0:0];
    wire [31:0] m_axi_araddr_t [0:0];
    wire [7:0]  m_axi_arid_t   [0:0];
    wire [7:0]  m_axi_arlen_t  [0:0];
    wire [2:0]  m_axi_arsize_t [0:0];
    wire [1:0]  m_axi_arburst_t[0:0];
    wire [1:0]  m_axi_arlock_t [0:0];
    wire [3:0]  m_axi_arcache_t[0:0];
    wire [2:0]  m_axi_arprot_t [0:0];
    wire [3:0]  m_axi_arqos_t  [0:0];
    wire [3:0]  m_axi_arregion_t[0:0];

    wire m_axi_rvalid_t [0:0];
    wire m_axi_rready_t [0:0];
    wire [511:0] m_axi_rdata_t [0:0];
    wire m_axi_rlast_t [0:0];
    wire [7:0] m_axi_rid_t   [0:0];
    wire [1:0] m_axi_rresp_t [0:0];

    Vortex_axi #(
        .AXI_DATA_WIDTH (512),
        .AXI_ADDR_WIDTH (32),
        .AXI_TID_WIDTH  (8),
        .AXI_NUM_BANKS  (1)
    ) vortex (
        .clk            (clk),
        .reset          (reset),

        .m_axi_awvalid  (m_axi_awvalid_t),
        .m_axi_awready  (m_axi_awready_t),
        .m_axi_awaddr   (m_axi_awaddr_t),
        .m_axi_awid     (m_axi_awid_t),
        .m_axi_awlen    (m_axi_awlen_t),
        .m_axi_awsize   (m_axi_awsize_t),
        .m_axi_awburst  (m_axi_awburst_t),
        .m_axi_awlock   (m_axi_awlock_t),
        .m_axi_awcache  (m_axi_awcache_t),
        .m_axi_awprot   (m_axi_awprot_t),
        .m_axi_awqos    (m_axi_awqos_t),
        .m_axi_awregion (m_axi_awregion_t),

        .m_axi_wvalid   (m_axi_wvalid_t),
        .m_axi_wready   (m_axi_wready_t),
        .m_axi_wdata    (m_axi_wdata_t),
        .m_axi_wstrb    (m_axi_wstrb_t),
        .m_axi_wlast    (m_axi_wlast_t),

        .m_axi_bvalid   (m_axi_bvalid_t),
        .m_axi_bready   (m_axi_bready_t),
        .m_axi_bid      (m_axi_bid_t),
        .m_axi_bresp    (m_axi_bresp_t),

        .m_axi_arvalid  (m_axi_arvalid_t),
        .m_axi_arready  (m_axi_arready_t),
        .m_axi_araddr   (m_axi_araddr_t),
        .m_axi_arid     (m_axi_arid_t),
        .m_axi_arlen    (m_axi_arlen_t),
        .m_axi_arsize   (m_axi_arsize_t),
        .m_axi_arburst  (m_axi_arburst_t),
        .m_axi_arlock   (m_axi_arlock_t),
        .m_axi_arcache  (m_axi_arcache_t),
        .m_axi_arprot   (m_axi_arprot_t),
        .m_axi_arqos    (m_axi_arqos_t),
        .m_axi_arregion (m_axi_arregion_t),

        .m_axi_rvalid   (m_axi_rvalid_t),
        .m_axi_rready   (m_axi_rready_t),
        .m_axi_rdata    (m_axi_rdata_t),
        .m_axi_rlast    (m_axi_rlast_t),
        .m_axi_rid      (m_axi_rid_t),
        .m_axi_rresp    (m_axi_rresp_t),

        .dcr_req_valid  (dcr_req_valid),
        .dcr_req_rw     (dcr_req_rw),
        .dcr_req_addr   (dcr_req_addr),
        .dcr_req_data   (dcr_req_data),
        .dcr_rsp_valid  (dcr_rsp_valid),
        .dcr_rsp_data   (dcr_rsp_data),

        .start          (start),
        .busy           (busy)
    );

    assign m_axi_awvalid = m_axi_awvalid_t[0];
    assign m_axi_awaddr  = m_axi_awaddr_t[0];
    assign m_axi_awid    = m_axi_awid_t[0];
    assign m_axi_awlen   = m_axi_awlen_t[0];
    assign m_axi_awsize  = m_axi_awsize_t[0];
    assign m_axi_awburst = m_axi_awburst_t[0];
    assign m_axi_awlock  = m_axi_awlock_t[0];
    assign m_axi_awcache = m_axi_awcache_t[0];
    assign m_axi_awprot  = m_axi_awprot_t[0];
    assign m_axi_awqos   = m_axi_awqos_t[0];
    assign m_axi_awregion= m_axi_awregion_t[0];

    assign m_axi_wvalid  = m_axi_wvalid_t[0];
    assign m_axi_wdata   = m_axi_wdata_t[0];
    assign m_axi_wstrb   = m_axi_wstrb_t[0];
    assign m_axi_wlast   = m_axi_wlast_t[0];

    assign m_axi_bready  = m_axi_bready_t[0];

    assign m_axi_arvalid = m_axi_arvalid_t[0];
    assign m_axi_araddr  = m_axi_araddr_t[0];
    assign m_axi_arid    = m_axi_arid_t[0];
    assign m_axi_arlen   = m_axi_arlen_t[0];
    assign m_axi_arsize  = m_axi_arsize_t[0];
    assign m_axi_arburst = m_axi_arburst_t[0];
    assign m_axi_arlock  = m_axi_arlock_t[0];
    assign m_axi_arcache = m_axi_arcache_t[0];
    assign m_axi_arprot  = m_axi_arprot_t[0];
    assign m_axi_arqos   = m_axi_arqos_t[0];
    assign m_axi_arregion= m_axi_arregion_t[0];

    assign m_axi_rready  = m_axi_rready_t[0];

    assign m_axi_awready_t[0] = m_axi_awready;
    assign m_axi_wready_t[0]  = m_axi_wready;
    assign m_axi_bvalid_t[0]  = m_axi_bvalid;
    assign m_axi_bid_t[0]     = m_axi_bid;
    assign m_axi_bresp_t[0]   = m_axi_bresp;
    assign m_axi_arready_t[0] = m_axi_arready;
    assign m_axi_rvalid_t[0]  = m_axi_rvalid;
    assign m_axi_rdata_t[0]   = m_axi_rdata;
    assign m_axi_rlast_t[0]   = m_axi_rlast;
    assign m_axi_rid_t[0]     = m_axi_rid;
    assign m_axi_rresp_t[0]   = m_axi_rresp;

endmodule