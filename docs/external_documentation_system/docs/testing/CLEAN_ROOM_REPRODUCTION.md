# Clean-Room Reproduction

An independent team must:

1. acquire and verify source;
2. establish the locked toolchain;
3. assemble the documented hardware;
4. run all SpinalSim tests;
5. generate Verilog;
6. synthesize the bitstream;
7. build `libvdp` and firmware;
8. program and flash;
9. run generic and platform acceptance;
10. compare hashes, counters, and expected images;
11. record deviations;
12. sign the report.

Release is blocked when undocumented private knowledge is required.
