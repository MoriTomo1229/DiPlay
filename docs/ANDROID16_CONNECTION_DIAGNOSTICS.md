# Android 16 connection diagnostics (life #176)

Upstream baseline: `shihabal3amri/DiPlay@842b647`. Public source contains the private-beta
report's P2P creation/error strings and USB wait strings; this establishes matching code
paths, not byte-for-byte equivalence with the private beta APK.

## Findings and changes

`shared/.../network/WifiP2pGroupManager.kt` creates configured groups using
`WifiP2pConfig.Builder.setGroupOperatingFrequency`. `P2pStartupRecovery.kt` already tries
5180, 5745, 2437, 2412, 2462 MHz, then the API 29 `createGroup(channel, null, listener)`
overload. That final overload uses system-selected frequency and credentials.
`ERROR=0` is converted to `generic error` by `failureReason`; it does not identify a
firmware cause. Permissions use Nearby Wi-Fi Devices on API 33+ and precise location
on API 29–32. No Xiaomi-specific branch was added.

On Xiaomi 24091RPADG / API 36 / station 5260 MHz, both 5 GHz requests were rejected,
but the 2437 MHz request succeeded and the group reported owner=true. The failure was
subsequent security metadata interpretation, not failure to become Group Owner.
`WifiP2pGroup.getSecurityType()` returned Android's `SECURITY_TYPE_UNKNOWN=-1` even after
re-query. Android defines this as failure to derive key management, not an open network.

The patch explicitly requests `PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY` on API 36+
(documented WPA2-Personal for Group Owner), waits up to 2 seconds for metadata, and
only resolves persistent unknown as WPA2 when both the returned network name and
passphrase match the explicit request. This is configuration provenance, not independent
radio verification. Known WPA3 values remain authoritative. Unknown system-selected
groups, missing credentials, mismatching groups, and other unsupported values still fail.
No credential is printed. Group queries are throttled while waiting for a group.

Sources:
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup#SECURITY_TYPE_UNKNOWN
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig#PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setPccModeConnectionType(int)

USB discovery is separately instrumented in `IphoneUsbHost.discover`. The ordinary
configuration matches all Apple products by VID `0x05ac`; an explicitly configured
VID/PID list narrows that match. Diagnostics show numeric bus path, VID/PID, device class,
interface count, permission and matcher result only, and report only changed inventories.
Serial number, manufacturer/product strings, authentication identity and keys are not read.
An empty UsbManager inventory cannot be repaired by changing the Apple matcher.

## Build and use

Use the existing JDK 25 / SDK 37.0 / NDK 28.2.13676358 toolchain:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb shell am start -n com.shihab.diplay.hudtest/com.shihab.diplay.ConnectionDiagnosticsActivity
```

This diagnostic APK installs alongside the existing `com.shihab.diplay` app. It contains
no runtime authentication assets and cannot run standalone CarPlay. The additional
connection diagnostics activity exists only in the debug build. It tests Wi-Fi without
entering MFi authentication. Stop the existing projection app before starting the probe.
Press **Test Wi-Fi Direct**, grant Nearby devices if requested, and press the button again.
Press **Stop Wi-Fi test** or leave the activity to remove the temporary group.

For USB, disconnect the PC, attach the iPhone through a data-capable cable/OTG adapter,
then press **Inspect USB devices**. Reconnect the PC and retrieve the selected metadata:

```sh
adb shell run-as com.shihab.diplay.hudtest cat files/connection-probe.log
```

Compare direct USB-C and OTG/adaptor results. A phone reported with `matched=true` and
`permission=false` is detected but not yet authorized; discovery itself does not request
permission. An empty list establishes only that Android has no host device in that test.

## Verified and outstanding

2026-09-30 JST: real-device probe reproduces the baseline failure, then the patched probe
completes P2P bring-up on 2437 MHz / channel 6 / WPA_WPA2. This does not prove iPhone
association, MFi authentication, CarPlay video/touch, 30fps
stability, or USB discovery with an attached iPhone. The last system-selected fallback
remains tested by the existing unit tests; this device accepted 2437 MHz before reaching it.

To build a full standalone test package, supply legitimately provisioned runtime assets
through `DIPLAY_AUTH_ASSETS_DIR` and use the existing `assembleStandaloneDebug` task.
Do not extract identities from the installed private beta APK. See BUILD.md.

Rollback: stop the probe, uninstall `com.shihab.diplay.hudtest`, or revert the patch commits
in the fork. The existing private beta app and its data remain installed.

## Prepared iPhone boundary

The debug activity now shows the temporary joining network/password locally only after
P2P startup succeeds; these credentials are never sent to diagnostics and the window is
marked FLAG_SECURE. It also has **Wait for iPhone USB**, which registers a USB attach
receiver and records initial discovery. Detection stops at the Apple matcher boundary;
it does not request permission, reconfigure USB, or start authentication.

Both paths are available without MFi assets. Closing the activity removes the temporary
P2P group and unregisters the USB receiver. The user confirmed no build-time runtime
authentication assets are available. The standalone build guard rejects that missing
input as expected; the full CarPlay session remains blocked on legitimate provisioning.

## Follow-up: network visibility

The user reported the network missing from the iPhone list. Both the original DiPlay and
the debug probe were running, and the probe saw repeated group formed/removed broadcasts.
After stopping the original app and recreating the probe group, a PC Wi-Fi rescan matched
the framework's current group network at 2437 MHz, advertised WPA2, signal 100 on
NetworkManager's relative scale. The label/password were not exported. This independently
confirms the beacon's visibility and advertised security in this test. It does not prove
iPhone association or identify which app requested the earlier removals. Keep other
projection apps stopped while testing, and use the current joining details after recreation.
