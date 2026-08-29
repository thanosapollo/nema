# Android signing-key rotation runbook

This is a reviewed, public procedure for a future signing-key rotation. This
campaign performs no rotation. Do not create keys, a signing lineage, or an APK
while reviewing this document.

## Fixed release contract

- Use Android SDK Build Tools 36.0.0 and `apksigner 36.0.0`.
- Set the explicit rotation floor to `--rotation-min-sdk-version 33`.
- Record these signer capabilities in the lineage: `installed-data=true`,
  `shared-uid=false`, `permission=false`, `rollback=false`, and `auth=false`.
- Treat the lineage as an immutable release artifact. Back it up outside Git in
  the same controlled, durable storage class as the signing material.
- Changing the tool, version, rotation floor, or any capability requires fresh
  review and fresh physical-device proof before release.

## Secret-safe command templates

Supply stores and output locations through local environment variables. Supply
credentials only through explicit `env:` references. Never place credentials
in Git, shell history, command output, or this runbook.

Create the lineage once:

```sh
apksigner rotate \
  --out "$LINEAGE_FILE" \
  --old-signer --ks "$OLD_SIGNER_STORE" \
  --ks-pass env:OLD_STORE_PASS \
  --key-pass env:OLD_KEY_PASS \
  --set-installed-data true \
  --set-shared-uid false \
  --set-permission false \
  --set-rollback false \
  --set-auth false \
  --new-signer --ks "$NEW_SIGNER_STORE" \
  --ks-pass env:NEW_STORE_PASS \
  --key-pass env:NEW_KEY_PASS
```

Sign the candidate with both signers and the reviewed rotation floor:

```sh
apksigner sign \
  --ks "$OLD_SIGNER_STORE" \
  --ks-pass env:OLD_STORE_PASS \
  --key-pass env:OLD_KEY_PASS \
  --next-signer \
  --ks "$NEW_SIGNER_STORE" \
  --ks-pass env:NEW_STORE_PASS \
  --key-pass env:NEW_KEY_PASS \
  --lineage "$LINEAGE_FILE" \
  --rotation-min-sdk-version 33 \
  --out "$SIGNED_APK" "$UNSIGNED_APK"
```

Verify the exact candidate before installation:

```sh
apksigner verify --verbose --print-certs --min-sdk-version 26 "$SIGNED_APK"
```

Review the reported signers and schemes against the approved release record.
Do not publish or install if any input, capability, version, or signer differs.

## Compatibility contract

- API 26-27: original current, exact signer continuity required.
- API 28-32: original current; the update must preserve installed app data.
- API 33+: new current, old -> new history; the platform must report the
  approved signing lineage.

## Evidence and physical proof

For each device, archive the release candidate identifier, device API level,
pre-update and post-update application identity, installer result, and the
PackageManager archive output used to establish current and historical signers.
Keep that evidence with the reviewed release record, outside the source tree.

Exercise an update over an existing installation on a physical API 26 device,
a physical API 28 device, and a physical API 33 device. Before the update,
create distinct sentinel content through the app. After the update, prove the
same unchanged sentinel data is present and usable. Also confirm that the
PackageManager archive matches the compatibility contract above.

Emulators and signature inspection may supplement these checks but cannot
replace the three physical-device results. Any change to the reviewed contract
invalidates prior evidence and requires another review and complete proof set.
