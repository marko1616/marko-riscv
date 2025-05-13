#pragma once
#include <cstdint>

struct axiSignal {
    // Write request signals
    bool awvalid;                  // Master
    bool awready;                  // Slave
    uint64_t awaddr;               // Master
    uint8_t awsize;                // Master (3 bits)
    uint8_t awburst;               // Master (2 bits)
    uint8_t awcache;               // Master (4 bits)
    uint8_t awprot;                // Master (3 bits)
    uint16_t awid;                 // Master (axid_len bits)
    uint8_t awlen;                 // Master (8 bits)
    uint8_t awlock;                // Master (1 bit)
    uint8_t awqos;                 // Master (4 bits)
    uint8_t awregion;              // Master (4 bits)

    // Write data signals
    bool wvalid;                   // Master
    bool wready;                   // Slave
    bool wlast;                    // Master
    uint64_t wdata;                // Master
    uint8_t wstrb;                 // Master (data_width / 8, assuming data_width = 32)

    // Write response signals
    bool bvalid;                   // Slave
    bool bready;                   // Master
    uint8_t bresp;                 // Slave (2 bits)
    uint16_t bid;                  // Slave (axid_len bits)

    // Read request signals
    bool arvalid;                  // Master
    bool arready;                  // Slave
    uint64_t araddr;               // Master
    uint8_t arsize;                // Master (3 bits)
    uint8_t arburst;               // Master (2 bits)
    uint8_t arcache;               // Master (4 bits)
    uint16_t arid;                 // Master (axid_len bits)
    uint8_t arlen;                 // Master (8 bits)
    uint8_t arlock;                // Master (1 bit)
    uint8_t arqos;                 // Master (4 bits)
    uint8_t arregion;              // Master (4 bits)
    uint8_t arprot;                // Master (3 bits)

    // Read data signals
    bool rvalid;                   // Slave
    bool rready;                   // Master
    bool rlast;                    // Slave
    uint64_t rdata;                // Slave
    uint8_t rresp;                 // Slave (2 bits)
    uint16_t rid;                  // Slave (axid_len bits)
};