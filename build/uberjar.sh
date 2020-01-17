#!/usr/bin/env bash
set -euo pipefail

echo "Cleaning classes directory..."

rm -rf classes

echo "Compiling Vessel..."

mkdir classes

clojure -e "(compile 'vessel.program)"

echo "Extracting compiled dependencies into classes directory..."

IFS=':' read -ra deps <<< $(clojure -Spath)

cd classes

for dep in "${deps[@]}"; do
    if [ -f $dep ]; then
        jar -xvf $dep
    fi
done

echo "Generating uberjar..."

jar -cvfe vessel.jar vessel.program .

cd ..

mkdir -p target

cp classes/vessel.jar target/

echo "Created " $(ls target/*.jar)
