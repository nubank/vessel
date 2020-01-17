#!/usr/bin/env bash
set -euo pipefail

classes_dir=target/classes

echo "Cleaning classes directory..."

rm -rf $classes_dir

echo "Compiling Vessel..."

mkdir -p $classes_dir

clojure -e "(binding [*compile-path* \"$classes_dir\"] (compile 'vessel.program))"

echo "Extracting compiled dependencies into classes directory..."

IFS=':' read -ra deps <<< $(clojure -Spath)

cd $classes_dir

for dep in "${deps[@]}"; do
    if [ -f $dep ]; then
        jar -xvf $dep
    fi
done

echo "Generating uberjar..."

jar -cvfe vessel.jar vessel.program .

cd ..; mv classes/vessel.jar .

echo "Created " $(ls *.jar)
