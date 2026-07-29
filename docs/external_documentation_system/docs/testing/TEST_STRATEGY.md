# Test Strategy

## Pyramid

1. pure conversion/unit tests;
2. SpinalHDL component tests;
3. adapter tests;
4. `VdpTop` integration;
5. contention/stress;
6. synthesis/static checks;
7. hardware conformance;
8. platform visual acceptance;
9. soak and reset;
10. clean-room reproduction.

Every requirement has at least one objective test. Visual inspection alone is
supporting evidence, not the primary oracle.
