# Porting libvdp to a New Host

## Procedure

1. select or implement a transport backend;
2. define board pins and electrical requirements;
3. implement initialization and reset;
4. implement register write/read;
5. implement burst and SDRAM upload;
6. implement status/vblank method;
7. implement timeouts and errors;
8. build the common API unchanged;
9. run the transport conformance suite;
10. run Generic Mode0 hardware proof;
11. record the host in the support matrix.

## Conformance tests

- magic/ABI read;
- register round trip;
- maximum burst;
- SDRAM write and diagnostic read/hash;
- malformed transaction recovery;
- reset recovery;
- vblank/status;
- long repeated transaction soak.

A build-only port is not labeled supported.
