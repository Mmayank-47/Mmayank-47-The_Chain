# The Chain - Running on an Android Phone

This guide describes how to run and test **The Chain** on a real Android phone in both **Laptop-Hosted** and **Fully On-Phone (Termux)** modes.

---

## Mode A: Laptop-Hosted (Recommended for Rapid Pair Development)

The Python gateway runs on your laptop, hosting the simulated PhoneNode mesh and serving the built frontend assets from the same origin (`http://0.0.0.0:8000`).

### 1. Build Frontend & Start Gateway
On your laptop terminal:
```bash
# 1. Build the production single-page application and offline PWA assets
cd frontend && npm run build && cd ..

# 2. Launch the standard library gateway server
python -m gateway.server --host 0.0.0.0 --port 8000
```

The gateway prints your detected LAN IP address:
```text
======================================================================
  THE CHAIN - DECENTRALIZED EMERGENCY MESH GATEWAY
======================================================================
  * Local URL:    http://localhost:8000
  * LAN URL:      http://192.168.1.45:8000
  * USB Debug:    adb reverse tcp:8000 tcp:8000 -> http://localhost:8000
  * Frontend:     /path/to/The_Chain/frontend/dist
  * Sim Nodes:    node-me, node-relay-1, node-resp-1, node-neighbor-2, node-detour
======================================================================
```

### 2. Option 1: Wi-Fi Access
1. Connect your Android phone to the **same Wi-Fi network or mobile hotspot** as your laptop.
2. Open Chrome or Firefox on your phone and navigate to:
   ```text
   http://<YOUR-LAN-IP>:8000
   ```
   (e.g., `http://192.168.1.45:8000`).

### 3. Option 2: USB Cable with ADB Reverse (Zero Wi-Fi Required)
If no local Wi-Fi router is available, you can bridge directly over USB:
1. Enable **USB Debugging** in your phone's Developer Options.
2. Connect your phone via USB cable and reverse port 8000:
   ```bash
   adb reverse tcp:8000 tcp:8000
   ```
3. Open Chrome on your phone and browse to:
   ```text
   http://localhost:8000
   ```

### 4. Chrome DevTools Remote Debugging
To inspect console logs, network events, and SSE streams on the real phone:
1. On your laptop's Chrome browser, navigate to:
   ```text
   chrome://inspect/#devices
   ```
2. Locate your phone under **Remote Target** and click **Inspect**.
3. View real-time console messages, network calls (`POST /api/claims`, `/api/events`), and DOM elements.

---

## Mode B: Fully On-Phone (Termux Standalone)

Run the entire Python mesh node and offline web server directly on the Android hardware without any computer attached.

### 1. Requirements
- Install **Termux** (recommended via [F-Droid](https://f-droid.org/packages/com.termux/)).

### 2. Setup & Execution
Open Termux on your phone and execute:
```bash
# Clone or copy repo to phone storage
cd ~/
git clone https://github.com/Mmayank-47/The_Chain.git
cd The_Chain

# Run automated setup script
bash scripts/termux_setup.sh
```

### 3. Accessing the App
1. Open Chrome on the phone and go to:
   ```text
   http://localhost:8000
   ```
2. Tap the Chrome menu (`⋮`) and select **"Add to Home screen"** or **"Install app"**.
3. The app installs as a standalone **Progressive Web App (PWA)**, pre-caching the shell via `sw.js` so it loads instantly even with Airplane Mode enabled.

---

## Verification Checklist on Phone

1. **Help Flow (Step 1 → Step 2 → Step 3)**:
   - Tap a category in Step 1.
   - Speak or type in Step 2. Notice the live M2 parsed chips and M3 confidence score update dynamically.
   - Tap **"Broadcast Distress Beacon"**.
   - In Step 3, observe real-time relay telemetry and the M8 auction state (`PROPOSED` → `ACCEPTED`).
   - Tap **"Abort Broadcast"** to cancel the lease in the CRDT book.
2. **Respond Screen**:
   - View live distress requests from the mesh.
   - Tap **"Accept Task"**.
   - Verify that **M9 stage-2 sealed details** (exact victim name, contact phone, and exact GPS coordinates) unlock.
3. **Map Screen**:
   - Inspect flood frontier advance and coarse grid cells.
4. **Mesh Screen**:
   - Tap **"Ping Mesh Neighbors"** to execute a live diagnostic round-trip.
   - Use the **Dev Chaos Controls** to test **Partition Mesh**, **Heal Partition**, and **Blackhole Attack**.
5. **Me Screen**:
   - Update emergency medical contact or blood group and tap **"Save"**.
   - Verify that changes are persisted to disk across app reloads.
