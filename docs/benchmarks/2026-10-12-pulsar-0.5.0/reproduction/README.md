# Reproduction

Extract the public raw bundle into a study root and place collect.py in tools/. Run it with Python 3 to recompute per-run metrics, output parity and median/range summaries. All 76 public trials are included. Follow protocol.json for workload commands and manifest.json for exact artifacts. Use a fresh JVM and frozen seed-1 world copy per trial. Engine binaries and Minecraft worlds are not distributed.

Measurement uses the same uninstrumented Lightbench 1.1.0-bench9 runtime (source 05596de) and offline comparator f6ccb91. The runtime includes the production engine completion adapters; reported completion latency includes their waiting behavior. It is not pure propagation CPU time.

To regenerate public PNG/SVG figures, place plot.py beside collect.py and install matplotlib 3.11.2 / numpy 2.5.3 into tools/python-packages, or make them available on PYTHONPATH. Set progress.json to {"status":"measurements-complete"}, then run collect.py followed by plot.py.
