#!/bin/bash

# This script releases a new version of ExpectJ
set -e
set -x

# Hello Sourceforge shell services
ssh johanwalles,expectj@shell.sf.net create

# Tag the release and submit updated pom.xml files.
mvn release:prepare

# Build and deploy the release
# The "-P release" is required because of MRELEASE-459
mvn -P release release:perform
