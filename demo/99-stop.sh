#!/usr/bin/env bash
pkill -f 'target/.*-0.0.1-SNAPSHOT.jar' && echo "stopped" || echo "nothing running"
