# Capability Discovery

## Required initialization flow

1. initialize transport;
2. read magic;
3. read ABI version;
4. read feature and adapter bitmaps;
5. read memory/limit data;
6. compare required capabilities;
7. reset/configure only after compatibility passes.

## Error cases

- no device;
- wrong magic;
- unsupported ABI major;
- missing adapter;
- missing required engine;
- insufficient memory/limits;
- transport lacks required read/status behavior.
