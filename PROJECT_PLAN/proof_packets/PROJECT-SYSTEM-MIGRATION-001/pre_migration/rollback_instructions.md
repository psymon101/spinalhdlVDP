# Rollback Instructions — PROJECT-SYSTEM-MIGRATION-001

## Pre-migration baseline

- Branch: `brightforge/ham-decoder-171`
- Commit: `958a01d61012a4043c78f330262db759d909eb73`
- Date: 2026-07-26

## Fast rollback (no migration commits yet)

If the migration has not advanced past Phase 1 and no migration commits exist
after `958a01d`:

```bash
cd /home/itadmin/github/spinalhdlVDP
git checkout 958a01d61012a4043c78f330262db759d909eb73
git branch -D brightforge/ham-decoder-171-migration-backup || true
git checkout -b brightforge/ham-decoder-171
```

## Rollback after migration commits

If migration commits exist after `958a01d`:

1. Preserve the migration branch:
   ```bash
   git branch brightforge/ham-decoder-171-migration-archive
   ```

2. Reset the working branch to the pre-migration commit:
   ```bash
   git checkout brightforge/ham-decoder-171
   git reset --hard 958a01d61012a4043c78f330262db759d909eb73
   ```

3. Restore `STATUS.md` consistency from the pre-migration snapshot:
   - Copy `PROJECT_PLAN/proof_packets/PROJECT-SYSTEM-MIGRATION-001/pre_migration/document_hashes.sha256` references.
   - Verify no new live-status documents were created.

4. Notify all agents via mail that rollback occurred and identify the exact
   trigger and recovery path.

## What must be preserved during rollback

- `STATUS.md` must remain the live-state authority.
- No orphaned live-status documents.
- No loss of committed hardware proof evidence.
