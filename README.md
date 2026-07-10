# USB Image Flasher (no root required)

A minimal "Raspberry Pi Imager"-style Android app that writes a raw `.img` file
(optionally `.gz` or `.xz` compressed) directly onto a USB flash drive — without
root.

## How it avoids needing root

Root is normally needed on Android because writing to `/dev/block/sdX` requires
kernel-level block-device access. This app sidesteps that entirely:

- It uses **USB Host mode (OTG)** via `android.hardware.usb.UsbManager` to talk
  to the USB drive as a raw USB device, not a mounted filesystem.
- It implements the **USB Mass Storage Bulk-Only Transport protocol** (the same
  protocol the Linux kernel's `usb-storage` driver speaks) itself, in Kotlin,
  using `UsbDeviceConnection.bulkTransfer()`.
- It issues raw **SCSI commands** (`INQUIRY`, `TEST UNIT READY`,
  `READ CAPACITY (10)`, `WRITE (10)`) straight to the drive's bulk endpoints.
- Stock Android does **not** bind a kernel driver to host-mode mass-storage
  devices by default, so claiming the USB interface directly from an app
  (`claimInterface(interface, force=true)`) succeeds without any special
  privileges. This is the same technique used by libraries like `libaums`.

Net effect: the app *is* the USB mass-storage driver for the duration it's
running, and writes sectors straight to the device — equivalent to
`dd if=image.img of=/dev/sdX bs=...` — with no root, no `WRITE_EXTERNAL_STORAGE`
permission, and no reliance on the OS mounting the drive.

## Requirements

- An Android device with **USB Host / OTG support** (most phones since ~2013).
- A USB-C-to-A adapter or OTG cable to connect the flash drive.
- Android Studio (Koala+) to build; targets `minSdk 24`.

## Using it

1. Plug in the USB drive via OTG.
2. Tap **Select Image File** and pick a `.img`, `.img.gz`, or `.img.xz` file.
3. Tap **Detect USB Drive** and grant the USB permission prompt.
4. Tap **Flash**, confirm the destructive warning, and wait for completion.

## Known limitations

- Only handles LUN 0 and drives addressable with 32-bit LBAs via `WRITE(10)`
  (i.e. up to ~2 TiB at 512-byte sectors) — large drives would need `WRITE(16)`.
- No verification pass after writing (could be added by reading blocks back
  and comparing checksums).
- If your OEM's Android build *does* auto-mount USB mass storage devices at
  the kernel level, claiming the interface may fail while it's mounted; use a
  file manager to unmount/eject the drive first if that happens.
- Only one target drive can be connected at a time (multiple simultaneous
  drives aren't disambiguated in the UI yet).

## Project structure

```
app/src/main/java/com/example/usbimageflasher/
  MainActivity.kt              UI + USB permission flow
  ImageFlasher.kt              decompression + chunked sector writing
  usb/MassStorageDevice.kt     Bulk-Only Transport / SCSI driver
```
