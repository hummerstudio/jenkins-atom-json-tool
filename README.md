# jenkins-atom-json-tool

JSON read / write — port of **pipeline-utility-steps（readJSON/writeJSON）** (约 90,000（utility-steps）) as an atom.

The read side is the point: a version or an image tag that lives in package.json should reach the next step
as a variable. Flattened keys (`a.b`) work as environment names only when they are legal ones.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
