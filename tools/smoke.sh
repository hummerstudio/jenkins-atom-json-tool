#!/usr/bin/env bash
# Read a nested value, write one back, merge another file; the flattening is what reaches the next step.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. read one value
dir="$(mktemp -d)"
cat >"$dir/package.json" <<'JSON'
{"name": "parcel", "version": "2.4.1", "build": {"image": "registry/parcel:2.4.1"},
 "keywords": ["a", "b"], "private": true, "count": 3}
JSON
printf '{"operation": "read", "file": "package.json", "path": "build.image"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "read failed: $(cat "$dir/log.txt")"
[ "$(output "$dir" value)" = "registry/parcel:2.4.1" ] || fail "value: $(cat "$dir/output.json")"
[ "$(output "$dir" type)" = "string" ] || fail "type: $(cat "$dir/output.json")"

# 2. read the whole document: nested keys are flattened, arrays keep their index
printf '{"operation": "read", "file": "package.json"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "flat read failed"
python3 - "$dir/output.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))["data"]
want = {"name": "parcel", "version": "2.4.1", "build.image": "registry/parcel:2.4.1",
        "keywords.0": "a", "private": "true", "count": "3"}
for key, value in want.items():
    got = data.get(key, {}).get("value")
    assert got == value, f"{key}: {got!r} != {value!r}"
print("flatten ok")
PY

# 3. write creates the file and the intermediate objects
dir="$(mktemp -d)"
printf '{}' >"$dir/input.json"
printf '{"operation": "write", "file": "meta/info.json", "path": "build.number", "value": "42", "asNumber": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "write failed: $(cat "$dir/output.json")"
python3 - "$dir/meta/info.json" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
assert doc["build"]["number"] == 42, doc
assert isinstance(doc["build"]["number"], int), "asNumber must store a number"
print("write ok")
PY

# 4. merge: the incoming file wins, nested objects merge
dir="$(mktemp -d)"
printf '{"a": 1, "nested": {"keep": 1, "replace": 1}}' >"$dir/target.json"
printf '{"nested": {"replace": 2}, "b": 3}' >"$dir/extra.json"
printf '{"operation": "merge", "file": "target.json", "from": "extra.json"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "merge failed: $(cat "$dir/output.json")"
python3 - "$dir/target.json" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
assert doc == {"a": 1, "nested": {"keep": 1, "replace": 2}, "b": 3}, doc
print("merge ok")
PY

# 5. a missing pointer is an error, not an empty string
dir="$(mktemp -d)"; printf '{"a": 1}' >"$dir/x.json"
printf '{"operation": "read", "file": "x.json", "path": "nope"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing pointer must fail"
grep -q "no value at 'nope'" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: nested read, flatten, typed write, merge, missing pointer"
