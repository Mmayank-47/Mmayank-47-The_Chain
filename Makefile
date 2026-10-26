# One-command entry points. Needs only the Python standard library (3.9+).
PY ?= python

.PHONY: compile test gateway-test attacks sim check clean

test:            ## run the whole unit-test suite
	$(PY) -m unittest discover -s tests -t . -v

gateway-test:    ## run gateway contract and SSE tests
	$(PY) -m unittest tests/test_gateway.py -v

attacks:         ## M11 red-team suite (exit 1 if any defense fails)
	$(PY) -m m11_redteam.attack_suite

sim:             ## baseline Monte-Carlo table
	$(PY) -m sim.run --runs 10

compile:         ## syntax-check every package
	$(PY) -m compileall -q core gateway m0_transport m1_identity m2_parser m3_verify m4_hazard m5_trust m6_battery m7_matching m8_merge m9_privacy m10_ux m11_redteam phone sim tests

check: compile test gateway-test attacks   ## what CI should run

clean:
	find . -name __pycache__ -type d -prune -exec rm -rf {} +
