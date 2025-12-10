🛠️ **Version 0.1.2 Released!** 🛠️

* ✅ Feat: Implemented Write-Back L1 Data Cache with Clean/Invalidate support.
* ✅ Feat: Implemented Physical Memory Attribute (PMA) checker.
* ✅ Feat: Implemented exception handling for memory access.
* ✅ Feat: Improved `FENCE.I` sequence (Clean D-Cache before Invalidating I-Cache).
* ✅ Feat: Added D-Cache cleanup trigger in emulator for accurate RAM dump verification.
* 🛠️ Refactor: Migrated core configuration format from JSON to YAML.
* 🛠️ Refactor: Unified Cache and AXI interface definitions.
* ⬆️ Chore: Bumped Chisel version to 7.0.0 and Scala to 2.13.16.
* 🧪 Test: Updated batched test scripts to handle D-Cache flushing via `.tohost`.

🛠️ **Version 0.1.1 Released!** 🛠️

* 🛠️ Chore: RAM init change hex to binary.
* 🛠️ Fix: outfire signal at instruction issuer.
* ✅ Feat: Support for atomic operation.
* ✅ Feat: Virtual uart.
* ✅ Feat: Basic boot sequence.
* ✅ Feat: Better build and compile implementation.
* ✅ Feat: Custom cpp & verilator based test workflow.
* ✅ Feat: Support for basic Zicsr.
* ✅ Feat: Support for basic interruption.
* ✅ Feat: Support for basic AXI4-Lite.
* 📚 Docs: Added readme.

🎉 **Version 0.1.0 Released!** 🎉

* 🛠️ Fix: register file debug port.
* 🛠️ Fix: command skip edge cases in instruction issuer.
* 🛠️ Fix: Multiple typos corrected.
* ✅ Feat: Implemented L1 Data Cache support.
* 📚 Docs: Added update log and tag.
* 📚 Docs: Added architecture diagram.

📝 **Previously Unlogged Updates** 📝

* ✅ Feat: Support for Immediate Instructions
* ✅ Feat: Support for Register-Register Instructions
* ✅ Feat: Support for Memory Instructions
* ✅ Feat: Support for Flow Control Instructions
* ✅ Feat: Implemented L1 Instruction Cache support
