#!/bin/bash

CLASSPATH=$(echo ./bifromq/lib/*.jar | tr ' ' ':')
MAIN_CLASS="org.apache.bifromq.starter.StandaloneStarter"

native-image --verbose \
-cp $CLASSPATH \
-H:ConfigurationFileDirectories=./META-INF/native-image \
-H:Name=bifromq \
--no-fallback --report-unsupported-elements-at-runtime \
$MAIN_CLASS
