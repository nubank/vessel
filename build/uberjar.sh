#!/usr/bin/env bash

set -euo pipefail

cur_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" > /dev/null && pwd )"

version=$1

classes_dir=$cur_dir/../target/classes

echo "Cleaning classes directory..."

rm -rf $classes_dir

echo "Compiling Vessel..."

mkdir -p $classes_dir

clojure -e "(binding [*compile-path* \"$classes_dir\"] (compile 'vessel.executable))"

echo "Extracting compiled dependencies into classes directory..."

IFS=':' read -ra deps <<< $(clojure -Spath)

cd $classes_dir

for dep in "${deps[@]}"; do
    if [ -f $dep ]; then
        jar -xvf $dep
    fi
done

rm META-INF/*.SF META-INF/*.RSA

echo "Generating uberjar..."

jar -cvfe vessel-$version.jar vessel.executable .

cd ..; mv classes/vessel-$version.jar .

echo "Created " $(ls *.jar)
