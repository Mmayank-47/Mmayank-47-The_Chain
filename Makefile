# One-command entry points. Needs only the Python standard library (3.9+).
PY ?= python

.PHONY: test attacks sim check clean

test:            ## run the whole unit-test suite
	$(PY) -m unittest discover -s tests -t . -v

attacks:         ## M11 red-team suite (exit 1 if any defense fails)
	$(PY) -m m11_redteam.attack_suite

sim:             ## baseline Monte-Carlo table
	$(PY) -m sim.run --runs 10

check: test attacks   ## what CI should run

clean:
	find . -name __pycache__ -type d -prune -exec rm -rf {} +
