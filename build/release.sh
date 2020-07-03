#!/usr/bin/env bash

################################################################################
### Releases a new version of Vessel.
################################################################################

set -euo pipefail

cur_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" > /dev/null && pwd )"

version=$1

target=$cur_dir/../target

dist=$target/dist

vessel_dir=$dist/vessel

vessel_tar_gz=$dist/vessel-$version.tar.gz

function get_today() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        gdate --utc +'%Y-%m-%d'
    else
        date --utc +'%Y-%m-%d'
    fi
}

function update_changelog() {
    local changelog=$cur_dir/../CHANGELOG.md
    local today=$(get_today)
    sed -ie "s/\(##\s*\[Unreleased\]\)/\1\n\n## [$version] - $today/g" $changelog
    git add $changelog; git commit -m "Release version $version."
    git push origin master
    rm ${changelog}e
}

function gen_uberjar() {
    source $cur_dir/uberjar.sh $version
    cp $target/vessel-$version.jar $vessel_dir
}

function render() {
    local param=$1
    local value=$2
    cat /dev/stdin | sed -e "s/{{$param}}/$value/g"
}

function copy_file() {
    local source=$1
    local target=$2
    local year=$(date +'%Y')
    cat $source \
        | render year $year \
        | render version $version > $target
}

function package_up() {
    cd $vessel_dir
    tar -czvf $vessel_tar_gz *
}

function create_release() {
    hub release create $version \
        --message "Vessel $version" \
        --message "A comprehensive changelog can be found at: https://github.com/nubank/vessel/blob/${version}/CHANGELOG.md" \
        --attach $dist/linux-installer-${version}.sh#"Linux installer" \
        --attach $dist/vessel-${version}.tar.gz#"Vessel archives"
}

dirty=$(git status --porcelain)

if [ ! -z "$dirty" ]; then
    >&2 echo "Error: your working tree is dirty. Aborting release."
    exit 1
fi

echo "Releasing Vessel $version"

mkdir -p $vessel_dir

update_changelog

gen_uberjar

copy_file $cur_dir/assets/linux-installer.sh $dist/linux-installer-$version.sh

copy_file $cur_dir/assets/vessel.sh $vessel_dir/vessel

cp $cur_dir/../LICENSE $vessel_dir

package_up

create_release
