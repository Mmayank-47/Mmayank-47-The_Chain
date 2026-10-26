#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# The Chain - Decentralized Emergency Mesh: Termux Standalone Setup Script
# ==============================================================================
# This script sets up and runs The Chain directly on an Android device via Termux.
# No root required. Runs offline without cellular or internet once installed.
# ==============================================================================

set -e

echo "=== [1/4] Updating Termux environment & dependencies ==="
pkg update -y
pkg install -y python git clang libffi openssl

echo "=== [2/4] Verifying Python standard library environment ==="
python3 --version

echo "=== [3/4] Testing Mesh Gateway test suite ==="
python3 -m unittest tests/test_gateway.py -v

echo "=== [4/4] Starting The Chain Gateway & Offline PWA ==="
echo "Access the app locally on this phone at: http://localhost:8000"
echo "Or from any nearby device on the same local hotspot at: http://0.0.0.0:8000"
echo ""

python3 -m gateway.server --host 0.0.0.0 --port 8000
