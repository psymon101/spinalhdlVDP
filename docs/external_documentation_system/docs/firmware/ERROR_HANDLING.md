# Error Handling

## Error classes

- invalid argument;
- not initialized;
- transport initialization;
- transmit/receive;
- incompatible ABI;
- unsupported capability;
- timeout;
- upload overflow/drop/short frame;
- late event;
- SDRAM/scanout underrun;
- engine busy or invalid trigger.

## Rules

- errors are never silently converted to success;
- sticky hardware errors have explicit clear semantics;
- APIs document whether errors are returned immediately or polled;
- reference applications print source, code, and corrective action;
- proof tests include negative/error cases.
