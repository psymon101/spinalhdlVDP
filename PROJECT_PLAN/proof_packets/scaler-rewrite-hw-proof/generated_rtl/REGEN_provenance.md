# Generated RTL provenance — scaler-rewrite-hw-proof

## Source
- Branch: `topazcliff/scaler-rewrite`
- RTL logic settled at commit `7f8dde6` (P4 timing fix: reciprocal-multiply + registered ScaleCoordGen source-coord outputs).
- Current branch HEAD at build time: `5f82f94` (adds only STATUS.md + `.gitignore` hygiene — no RTL change).

## Regeneration (clean source → Verilog)
Command:
```
sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"   # TopTang20kHdmi(enableL1Fetch = false)
```
Result: `hw/gen/top_tang20k.v`
- P4-time hash (proof packet of external-review-scaler-rewrite): `b246aed77237c9af8c42d60c80da40686c1daec6dfa9354dd4f0d5cbc2b26e46`
- Regenerated-now hash: `662dcfad52c017cec92b16c881ca361f26b791e4c4310f47645cd1e108212704`

## Reproducibility verdict: IDENTICAL LOGIC
`diff` of the two files = **2 lines**, and the only change is the SpinalHDL header
provenance comment:
```
< // Git hash  : 7f8dde6a6bfc311afa92ac3cca4135f6dc0d0199   (P4 build HEAD)
> // Git hash  : 5f82f9448efec5fc6c0ec6a33b6d323565487af7   (current branch HEAD)
```
No RTL body difference. SpinalHDL stamps the working-tree HEAD commit into the
`// Git hash` header; the source is unchanged, so only that comment updates. The
`662dcfad` Verilog is therefore logically equivalent to the `b246aed7` Verilog
that passed P4 PnR (clk_pixel TNS=0, Fmax 30.705 MHz), and is the artifact used
for this hardware lane's fresh Gowin build.

Generator: SpinalHDL v1.12.3 (git head 591e6406). Warnings: routine `readAsync
can only be write first` (pre-existing, unrelated to scaler); 9635 pruned signals.
