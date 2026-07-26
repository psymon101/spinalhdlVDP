# Generate Verilog

1. Verify clean Scala source and tool versions.
2. Remove the generated output directory.
3. run the production SpinalHDL generator;
4. capture generator metadata;
5. verify top/module interface;
6. run stale-generated-file check;
7. hash generated RTL.

Never hand-edit generated Verilog.
