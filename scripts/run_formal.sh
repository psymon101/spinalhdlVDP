#!/bin/bash
# Wrapper script to execute formal verification tests.
# This ensures that the fully-featured OSS CAD Suite is used, resolving
# solver dependency issues (e.g., missing yices-smt2) present in the default
# system packages.

export PATH="/home/itadmin/oss-cad-suite/bin:$PATH"

if [ "$#" -eq 0 ]; then
    echo "Running all tests with OSS CAD Suite in PATH..."
    sbt test
else
    echo "Running sbt with arguments: $@"
    sbt "$@"
fi
