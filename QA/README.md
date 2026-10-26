# QA runners

## One-shot Suites 1–3

```powershell
# Windows
.\QA\run_all.ps1
```

```bash
# macOS / Linux
./QA/run_all.sh
```

- **Suite 1** — JVM unit + golden vectors across modules  
- **Suite 2** — `:sim:test` multi-node logic (not phone proof)  
- **Suite 3** — `assertShippingClasspath` + release merged-manifest INTERNET check  

Scripts **exit non-zero** if Suite 1–3 fail.

## Suite 4 / 5

| Suite | Requirement | Script behavior |
|-------|-------------|-----------------|
| 4 | ≥1 ADB phone | Message OPEN / run evidence under `QA/evidence/suite4/` |
| 5 | ≥2 ADB phones | Stays **OPEN** until second phone attached |

Do **not** mark Suite 5 PASS from this script.

## Evidence dirs

- `QA/evidence/m4/` — first boot / FGS  
- `QA/evidence/m5/` — Help/Respond single-phone  
- `QA/evidence/suite4/` — reboot, a11y, airplane  
