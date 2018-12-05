#!/usr/bin/env bash

if [ ! -f '.wren-deploy.rc' ]; then
  echo "Run this script from the project root." >&2
elif [ $# -ne 1 ]; then
  echo "Usage: ${0} <version>" >&2
else
  version="${1}"
  
  find . -type f \
    -regex '.*\.\(jar\|war\|pom\|zip\)\(.asc\)?$' \
    -name "*${version}*" \
    -wholename "*/target/*" \
    ! -wholename "./target/*" \
    -exec cp -v '{}' ./target ';'
fi