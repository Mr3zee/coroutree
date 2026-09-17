#!/bin/sh
# Builds and runs the monitor-contention spike (DESIGN.md §11.7). Needs a JDK (JAVA_HOME or macOS java_home) and a C compiler.
# Results of the run this decision was based on: docs/spikes/monitor-contention.md
set -e
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
OUT=build
mkdir -p "$OUT/classes"
"$JAVA_HOME/bin/javac" -d "$OUT/classes" src/spike/*.java

case "$(uname -s)" in
    Darwin) PLATFORM=darwin; LIB="$OUT/libjvmtiprobe.dylib"; SHARED="-dynamiclib" ;;
    *)      PLATFORM=linux;  LIB="$OUT/libjvmtiprobe.so";    SHARED="-shared -fPIC" ;;
esac
cc -O2 $SHARED -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$PLATFORM" -o "$LIB" jvmti_probe.c
ls -l "$LIB"

# threads, long holds per thread (2 ms), short holds per thread (20 µs), wait/notify rounds
ARGS="${SPIKE_ARGS:-4 50 20000 50}"
JAVA="$JAVA_HOME/bin/java -cp $OUT/classes"

for round in 1 2 3; do
    echo "=== round $round"
    $JAVA spike.JfrSpike $ARGS baseline
    $JAVA spike.JfrSpike $ARGS
    $JAVA "-agentpath:$LIB" spike.JvmtiSpike $ARGS
    $JAVA "-agentpath:$LIB" spike.JvmtiSpike $ARGS stacks
    $JAVA "-Dspike.load=$PWD/$LIB" spike.JvmtiSpike $ARGS stacks
done
