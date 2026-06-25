# Contributing Support for New Thermal Printers

Thank you for helping expand printer compatibility! Even if you are not a developer, you can provide the information needed to add support for your thermal printer.

In most cases, **you do not need to own any special hardware or write any code**. Simply collecting Bluetooth information and a few screenshots is enough.

---

# Step 1 – Tell us about your printer

Please provide:

- Manufacturer (e.g. Phomemo)
- Model (e.g. M110)
- Firmware version (if available)
- Android phone model
- Android version

---

# Step 2 – Bluetooth Information

Install **nRF Connect for Mobile** from the Play Store.

1. Turn on your printer.
2. Open nRF Connect.
3. Scan for Bluetooth devices.
4. Find your printer.
5. Tap **Connect**.
6. Wait until all services are discovered.

Please provide either:

- an exported device report (preferred), or
- screenshots of:
  - Advertisement information
  - Services
  - Characteristics

The following information is especially useful:

- Device Name
- MAC Address
- Manufacturer Data
- Service UUIDs
- Writable Characteristics
- Notify Characteristics

---

# Step 3 – Bluetooth HCI Snoop Log (Highly Recommended)

This allows us to reverse engineer the communication protocol if your printer is not already supported.

## Enable HCI Logging

On Android:

Settings → About Phone

Tap **Build Number** seven times to enable Developer Options.

Then:

Settings → Developer Options

Enable:

**Bluetooth HCI snoop log**

---

## Capture a Print

1. Enable Bluetooth HCI logging.
2. Restart Bluetooth (or reboot the phone).
3. Open the manufacturer's official printing app.
4. Print a very small test label (for example "Hello").
5. Disable Bluetooth HCI logging.

The log file is usually located at:

```
/sdcard/btsnoop_hci.log
```

or can be shared directly from Android depending on the device.

Compress the file into a ZIP before uploading.

---

# Step 4 – Printer Specifications

Please tell us:

- Maximum label width
- Supported label widths
- Resolution (DPI) if known
- Continuous labels or die-cut labels
- Black mark or gap sensing
- Maximum printable width

If you have a user manual, attach it.

---

# Step 5 – Photos

Please include:

- Front of the printer
- Label compartment
- Bottom sticker (model number)
- Bluetooth pairing screen
- Manufacturer app showing the connected printer

---

# Step 6 – Open a GitHub Issue

Please include:

- Printer model
- Phone model
- Android version
- Everything collected above

Attach:

- nRF Connect export
- Bluetooth HCI log (ZIP)
- Photos
- Manual (if available)

---

# Optional: Developer Information

If you are a developer, the following information is extremely helpful:

- BLE MTU
- Write characteristic UUID
- Notify characteristic UUID
- Write type
  - Write With Response
  - Write Without Response
- Packet size
- Command protocol
- Initialization commands
- Print commands
- End-of-job command

---

# Privacy

The Bluetooth HCI log only contains Bluetooth traffic between your phone and nearby devices. While it normally does not contain personal information, please review it before sharing if you have privacy concerns.

---

# Thank You!

Every new printer supported by the project starts with information provided by users like you. Even if you can't write code, your logs and screenshots can make your printer compatible for everyone.
