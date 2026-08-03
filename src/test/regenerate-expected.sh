#!/usr/bin/env bash
#
# Regenerate the expected stdout/stderr fixtures used by the antrun CLI
# integration test in pom.xml.
#
# Usage:  mvn package && ./src/test/regenerate-expected.sh
#
# The exit code observed for each case is printed so it can be compared with
# the "expected" attribute of the corresponding <jjval-case/> in pom.xml.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
res="${here}/resources"
jar="${here}/../../target/jjval.jar"

if [ ! -f "${jar}" ]; then
  echo "ERROR: ${jar} not found - run 'mvn package' first" >&2
  exit 1
fi

run_case() { # mode case args...
  mode="$1"; shift
  case_name="$1"; shift
  ( cd "${res}" && java -jar "${jar}" -nv "-${mode}" "$@" \
      > "${mode}.expected.${case_name}.stdout.txt" \
      2> "${mode}.expected.${case_name}.stderr.txt" )
  printf '%-3s %-24s exit=%s\n' "${mode}" "${case_name}" "$?"
}

for mode in vj ve vn vk vf; do
  run_case "${mode}" product.ok              -s product.schema.json  product.ok.json
  run_case "${mode}" product.err.syntax      -s product.schema.json  product.err.syntax.json
  run_case "${mode}" product.err.validation  -s product.schema.json  product.err.validation.json
  run_case "${mode}" products.ok             -s products.schema.json products.ok.json
  run_case "${mode}" products.err.syntax     -s products.schema.json products.err.syntax.json
  run_case "${mode}" products.err.validation -s products.schema.json products.err.validation.json
done

for mode in pj pe pn; do
  run_case "${mode}" product.ok              product.ok.json
  run_case "${mode}" product.err.syntax      product.err.syntax.json
  run_case "${mode}" product.err.validation  product.err.validation.json
  run_case "${mode}" products.ok             products.ok.json
  run_case "${mode}" products.err.syntax     products.err.syntax.json
  run_case "${mode}" products.err.validation products.err.validation.json
done

run_case vx product.ok      -d product.dtd  product.ok.xml
run_case vx products.ok     -d products.dtd products.ok.xml
run_case vx product.err.dtd -d products.dtd product.ok.xml

run_case vs product.ok      -s product.xsd  product.ok.xml
run_case vs products.ok     -s products.xsd products.ok.xml

run_case vy product.ok              -s product.schema.json product.ok.yaml
run_case vy product.err.syntax      -s product.schema.json product.err.syntax.yaml
run_case vy product.err.validation  -s product.schema.json product.err.validation.yaml

