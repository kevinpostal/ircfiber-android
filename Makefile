# UI tests run through TestLab (tools/testlab, vendored from vanlife) on Gradle-managed emulators: queued device slots
# shared by every session on this Mac, one build, the suite sharded over 2 emulators. Results in build/testlab/runs/latest/.
TESTLAB := python3 tools/testlab

.PHONY: uitest uitest-smoke uitest-failed uitest-only testlab-selftest

uitest:
	$(TESTLAB) android --tier full

uitest-smoke:
	$(TESTLAB) android --tier smoke

uitest-failed:
	$(TESTLAB) android --tier failed

# ONLY is a regex over Class#method ids; $(value …) keeps a trailing `$` anchor that make would otherwise expand away.
# Sessions layout + confirmation: make uitest-only ONLY='LoginSessionsLayoutTest' (320dp, 200% text).
uitest-only:
	$(TESTLAB) android --only '$(value ONLY)'

# TestLab's own unit tests (no device).
testlab-selftest:
	python3 -m unittest discover -s tools/testlab/tests -t tools/testlab/tests
