"""Contract and integration tests for The Chain Gateway (REST, SSE, and multi-node mesh propagation)."""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import threading
import time
import unittest
from http.client import HTTPConnection
from urllib.request import Request, urlopen

from gateway.server import GatewayRequestHandler
from gateway.state import GatewayState
from http.server import ThreadingHTTPServer


class TestGatewayServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmpdir = tempfile.mkdtemp()
        cls.dist_dir = os.path.join(cls.tmpdir, "dist")
        os.makedirs(cls.dist_dir, exist_ok=True)
        with open(os.path.join(cls.dist_dir, "index.html"), "w", encoding="utf-8") as f:
            f.write("<!DOCTYPE html><html><body>The Chain App Shell</body></html>")

        cls.state = GatewayState(profile_path=os.path.join(cls.tmpdir, "profile.json"),
                                 persistence_dir=os.path.join(cls.tmpdir, "nodes"))
        cls.state.start()

        class Handler(GatewayRequestHandler):
            pass

        Handler.state = cls.state
        Handler.dist_dir = cls.dist_dir

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.port = cls.server.server_address[1]
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.state.stop()
        cls.server.shutdown()
        cls.server.server_close()
        shutil.rmtree(cls.tmpdir, ignore_errors=True)

    def _req(self, method: str, path: str, body: dict = None) -> tuple[int, dict]:
        conn = HTTPConnection("127.0.0.1", self.port)
        headers = {"Content-Type": "application/json"} if body is not None else {}
        data = json.dumps(body).encode("utf-8") if body is not None else None
        conn.request(method, path, body=data, headers=headers)
        res = conn.getresponse()
        raw = res.read().decode("utf-8")
        conn.close()
        try:
            return res.status, json.loads(raw)
        except Exception:
            return res.status, {"_raw": raw}

    # ---- 1. Contract Tests -------------------------------------------------------------------
    def test_post_claims_valid(self):
        status, data = self._req("POST", "/api/claims", {
            "text": "Elderly grandmother needs oxygen cylinders near Shivaji Nagar Lane 2",
            "lang": "en",
            "pos": [155.0, 255.0]
        })
        self.assertEqual(status, 200)
        self.assertIn("claim_id", data)
        self.assertEqual(len(data["claim_id"]), 16)
        self.assertIn("parsed", data)
        self.assertIn("p_valid", data)
        self.assertGreater(data["p_valid"], 0.0)
        self.assertIn("trust", data)
        self.assertIn("battery", data)
        self.assertIn("gate", data)
        self.assertIn("disclosure", data)
        self.assertIn("justification", data)
        self.assertIn("risk", data["justification"])
        self.assertIn("abort", data["justification"])

    def test_claim_broadcast_and_abort(self):
        # 1. Analyze claim
        status, data = self._req("POST", "/api/claims", {
            "text": "Need clean drinking water, 3 packets, family stranded",
            "lang": "en"
        })
        self.assertEqual(status, 200)
        cid = data["claim_id"]

        # 2. Broadcast claim
        b_status, b_data = self._req("POST", f"/api/claims/{cid}/broadcast")
        self.assertEqual(b_status, 200)
        self.assertTrue(b_data["ok"])
        self.assertEqual(b_data["claim_id"], cid)
        self.assertIn("event_hash", b_data)

        # 3. Check requests includes this claim
        r_status, r_data = self._req("GET", "/api/requests")
        self.assertEqual(r_status, 200)
        req_ids = [r["id"] for r in r_data["requests"]]
        self.assertIn(cid, req_ids)

        # 4. Abort claim
        d_status, d_data = self._req("DELETE", f"/api/claims/{cid}")
        self.assertEqual(d_status, 200)
        self.assertTrue(d_data["ok"])
        self.assertTrue(d_data["aborted"])

    def test_respond_request_lifecycle(self):
        # Create and broadcast claim
        _, data = self._req("POST", "/api/claims", {"text": "Diabetic insulin kit required urgently"})
        cid = data["claim_id"]
        self._req("POST", f"/api/claims/{cid}/broadcast")

        # Accept request
        status, res = self._req("POST", f"/api/requests/{cid}/respond", {"action": "accept"})
        self.assertEqual(status, 200)
        self.assertTrue(res["ok"])
        self.assertEqual(res["state"], "accepted")
        self.assertIn("sealed_released", res)
        # Verify stage-2 sealed details unlocked
        sealed = res["sealed_released"]
        self.assertIn("exact_pos", sealed)
        self.assertIn("contact_phone", sealed)
        self.assertIn("victim_name", sealed)

    def test_get_mesh_and_map(self):
        # Mesh endpoint
        m_status, m_data = self._req("GET", "/api/mesh")
        self.assertEqual(m_status, 200)
        self.assertEqual(m_data["node_id"], "node-me")
        self.assertIn("neighbors", m_data)
        self.assertGreater(len(m_data["neighbors"]), 0)
        self.assertIn("dropped_counters", m_data)
        self.assertIn("airtime", m_data)

        # Map endpoint
        map_status, map_data = self._req("GET", "/api/map")
        self.assertEqual(map_status, 200)
        self.assertIn("flood_level_m", map_data)
        self.assertIn("hazard_cells", map_data)
        self.assertIn("entities", map_data)
        # Ensure exact GPS is NOT exposed in entities
        for ent in map_data["entities"]:
            self.assertIn("coarse_pos", ent)
            self.assertNotIn("exact_pos", ent)

    def test_me_profile_persistence(self):
        # Read profile
        status, profile = self._req("GET", "/api/me")
        self.assertEqual(status, 200)
        self.assertEqual(profile["fullName"], "Suman Kulkarni")

        # Update profile
        u_status, u_profile = self._req("PUT", "/api/me", {
            "fullName": "Aarav Deshmukh",
            "bloodGroup": "B+ (Positive)",
            "emergencyContact": "+91 99887 76655"
        })
        self.assertEqual(u_status, 200)
        self.assertEqual(u_profile["fullName"], "Aarav Deshmukh")
        self.assertEqual(u_profile["bloodGroup"], "B+ (Positive)")

        # Verify persisted
        r_status, r_profile = self._req("GET", "/api/me")
        self.assertEqual(r_profile["fullName"], "Aarav Deshmukh")

    # ---- 2. Input Validation & Error Handling -------------------------------------------------
    def test_malformed_input_rejections(self):
        # Empty text
        status, data = self._req("POST", "/api/claims", {"text": "   "})
        self.assertEqual(status, 400)
        self.assertEqual(data["code"], "INVALID_TEXT")

        # Oversized text
        status, data = self._req("POST", "/api/claims", {"text": "A" * 1001})
        self.assertEqual(status, 400)
        self.assertEqual(data["code"], "INVALID_TEXT")

        # Invalid language
        status, data = self._req("POST", "/api/claims", {"text": "Need help", "lang": "fr"})
        self.assertEqual(status, 400)
        self.assertEqual(data["code"], "INVALID_LANG")

        # Invalid action in respond
        status, data = self._req("POST", "/api/requests/claim-123/respond", {"action": "destroy"})
        self.assertEqual(status, 400)
        self.assertEqual(data["code"], "INVALID_ACTION")

    # ---- 3. Server-Sent Events (SSE) Ordering ------------------------------------------------
    def test_sse_stream_events(self):
        conn = HTTPConnection("127.0.0.1", self.port)
        conn.request("GET", "/api/events")
        res = conn.getresponse()
        self.assertEqual(res.status, 200)
        self.assertEqual(res.getheader("Content-Type"), "text/event-stream")

        # Read initial greeting event
        first_chunk = res.readline().decode("utf-8")
        self.assertTrue(first_chunk.startswith("event:") or first_chunk.startswith(":"))

        # Trigger an event from the API
        _, claim = self._req("POST", "/api/claims", {"text": "SSE broadcast test claim"})
        cid = claim["claim_id"]
        self._req("POST", f"/api/claims/{cid}/broadcast")

        # Read until claim_opened event received or timeout
        received_event = False
        start_t = time.time()
        while time.time() - start_t < 4.0:
            line = res.readline().decode("utf-8").strip()
            if line.startswith("event: claim_opened"):
                received_event = True
                break

        conn.close()
        self.assertTrue(received_event)

    # ---- 4. End-to-End Multi-Node Propagation over SimMedium ---------------------------------
    def test_claim_propagates_to_peer_node(self):
        """Claim broadcast on node-me propagates across SimMedium to node-resp-1's replica."""
        # Broadcast claim on node-me
        _, claim = self._req("POST", "/api/claims", {
            "text": "Multi-hop emergency signal across mesh corridor",
            "pos": [150.0, 250.0]
        })
        cid = claim["claim_id"]
        self._req("POST", f"/api/claims/{cid}/broadcast")

        # Run simulation steps for multi-hop packet traversal: node-me -> node-relay-1 -> node-resp-1
        for _ in range(15):
            self.state.tick(dt=1.0)

        # Check node-resp-1's replica book
        resp_node = self.state.nodes["node-resp-1"]
        self.assertIsNotNone(resp_node.replica.book.claims.get(cid))


if __name__ == "__main__":
    unittest.main()
