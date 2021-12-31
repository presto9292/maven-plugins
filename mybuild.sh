#!/bin/bash

set -e
set -o pipefail

clear
~/devtools/maven/bin/mvn clean install
