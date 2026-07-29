# Proof Packets

Proof packets hold actual evidence. They do not replace specifications.

Create one directory per closed task or lane:

```text
<Lane-or-task>/
├── manifest.yaml
├── source.txt
├── tool_versions.txt
├── simulation/
├── synthesis/
├── firmware/
├── hardware/
├── captures/
├── hashes.sha256
└── review.md
```

The packet must identify matched source, generated RTL, bitstream, firmware,
wiring revision, test assets, expected results, actual results, and reviewer
approval.
