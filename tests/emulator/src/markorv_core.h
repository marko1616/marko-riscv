// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Primary model header
//
// This header should be included by all source files instantiating the design.
// The class here is then constructed to instantiate the design.
// See the Verilator manual for examples.

#ifndef VERILATED_VMARKORVCORE_H_
#define VERILATED_VMARKORVCORE_H_  // guard

#include "verilated.h"

class VMarkoRvCore__Syms;
class VMarkoRvCore___024root;

// This class is the main interface to the Verilated model
class alignas(VL_CACHE_LINE_BYTES) VMarkoRvCore VL_NOT_FINAL : public VerilatedModel {
  private:
    // Symbol table holding complete model state (owned by this class)
    VMarkoRvCore__Syms* const vlSymsp;

  public:

    // CONSTEXPR CAPABILITIES
    // Verilated with --trace?
    static constexpr bool traceCapable = false;

    // PORTS
    // The application code writes and reads these signals to
    // propagate new values into/out from the Verilated model.
    VL_IN8(&clock,0,0);
    VL_IN8(&reset,0,0);
    VL_OUT8(&io_axi_awvalid,0,0);
    VL_IN8(&io_axi_awready,0,0);
    VL_OUT8(&io_axi_awprot,2,0);
    VL_OUT8(&io_axi_wvalid,0,0);
    VL_IN8(&io_axi_wready,0,0);
    VL_OUT8(&io_axi_wstrb,7,0);
    VL_IN8(&io_axi_bvalid,0,0);
    VL_OUT8(&io_axi_bready,0,0);
    VL_IN8(&io_axi_bresp,1,0);
    VL_OUT8(&io_axi_arvalid,0,0);
    VL_IN8(&io_axi_arready,0,0);
    VL_OUT8(&io_axi_arprot,2,0);
    VL_IN8(&io_axi_rvalid,0,0);
    VL_OUT8(&io_axi_rready,0,0);
    VL_IN8(&io_axi_rresp,1,0);
    VL_IN8(&io_debug_async_flush,0,0);
    VL_OUT8(&io_debug_async_outfire,0,0);
    VL_OUT64(&io_axi_awaddr,63,0);
    VL_OUT64(&io_axi_wdata,63,0);
    VL_OUT64(&io_axi_araddr,63,0);
    VL_IN64(&io_axi_rdata,63,0);
    VL_OUT64(&io_pc,63,0);
    VL_OUT64(&io_instr_now,63,0);
    VL_OUT64(&io_peek,63,0);

    // CELLS
    // Public to allow access to /* verilator public */ items.
    // Otherwise the application code can consider these internals.

    // Root instance pointer to allow access to model internals,
    // including inlined /* verilator public_flat_* */ items.
    VMarkoRvCore___024root* const rootp;

    // CONSTRUCTORS
    /// Construct the model; called by application code
    /// If contextp is null, then the model will use the default global context
    /// If name is "", then makes a wrapper with a
    /// single model invisible with respect to DPI scope names.
    explicit VMarkoRvCore(VerilatedContext* contextp, const char* name = "TOP");
    explicit VMarkoRvCore(const char* name = "TOP");
    /// Destroy the model; called (often implicitly) by application code
    virtual ~VMarkoRvCore();
  private:
    VL_UNCOPYABLE(VMarkoRvCore);  ///< Copying not allowed

  public:
    // API METHODS
    /// Evaluate the model.  Application must call when inputs change.
    void eval() { eval_step(); }
    /// Evaluate when calling multiple units/models per time step.
    void eval_step();
    /// Evaluate at end of a timestep for tracing, when using eval_step().
    /// Application must call after all eval() and before time changes.
    void eval_end_step() {}
    /// Simulation complete, run final blocks.  Application must call on completion.
    void final();
    /// Are there scheduled events to handle?
    bool eventsPending();
    /// Returns time at next time slot. Aborts if !eventsPending()
    uint64_t nextTimeSlot();
    /// Trace signals in the model; called by application code
    void trace(VerilatedTraceBaseC* tfp, int levels, int options = 0) { contextp()->trace(tfp, levels, options); }
    /// Retrieve name of this model instance (as passed to constructor).
    const char* name() const;

    // Abstract methods from VerilatedModel
    const char* hierName() const override final;
    const char* modelName() const override final;
    unsigned threads() const override final;
    /// Prepare for cloning the model at the process level (e.g. fork in Linux)
    /// Release necessary resources. Called before cloning.
    void prepareClone() const;
    /// Re-init after cloning the model at the process level (e.g. fork in Linux)
    /// Re-allocate necessary resources. Called after cloning.
    void atClone() const;
  private:
    // Internal functions - trace registration
    void traceBaseModel(VerilatedTraceBaseC* tfp, int levels, int options);
};

#endif  // guard