# SemanticDB output in native and imported builds

ScalaDock keeps SemanticDB enabled for code navigation and tooling. Each module
passes its own base directory as `-sourceroot`; sbt retains its configured
`semanticdbTargetRoot` under the module target directory. The same setting
applies to main and test compilation through `commonSettings`.

A consumer may import this build while running sbt from another working
directory. Without an explicit source root, the observed Scala 3.7.0 composite
build wrote 15 `.scala.semanticdb` files alongside core/fx sources even though
`-semanticdb-target` named a managed directory. The exact provider guard correctly
rejected those untracked files. Declaring the module source root fixes this
without disabling SemanticDB or ignoring generated source-tree files.

Verify the native build:

```sh
sbt core/test fx/test demo/compile
python3 tools/check-semanticdb-output.py --modules core fx demo --include-tests
```

The checker requires nonempty metadata for every selected Scala source at its
module-relative location below `target`, and rejects source-adjacent SemanticDB
files anywhere under `modules`. It emits source/output hashes as JSON. Run it
after compilation; it checks the resulting files rather than launching sbt.
It does not certify protobuf contents or require a new compilation when sbt is
already up to date.

For an imported build, first compile the consumer with these module targets
fresh, then run this checkout's checker with `--root /absolute/scaladock-checkout`
and `--modules core fx`. The source tree must remain clean without cleanup after
that consumer build. Existing unrelated working changes remain separate from
this output check.

On 2026-09-06, native verification passed 64 core tests, 26 JavaFX tests and demo compilation;
36 main/test metadata files were under managed targets. Consumer integration
and its exact dependency receipt are recorded by PLS Neuro's provider contract
and verification documents. Native Mote: `bd-01M1W4P96B4X7W51S1AFZZ2CJR`.

The [Scala compiler option mapping](https://docs.scala-lang.org/scala3/guides/migration/options-lookup.html#semanticdb)
documents native SemanticDB enablement and target options; the
[Scala source-root documentation](https://www.scala-lang.org/api/3.3_LTS/docs/docs/internals/coverage.html)
also identifies the source root's role in SemanticDB paths.
