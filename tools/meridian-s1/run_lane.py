#!/usr/bin/env python3
"""Reproduce S1's named-module runtime lanes on the recorded macOS/aarch64 host."""
import argparse,json,os,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument("lane",choices=("floor","current","native","scene"))
p.add_argument("--output",type=Path)
args=p.parse_args()
version="24.0.1" if args.lane=="floor" else "27"
jdk=Path("/Users/bbuchsbaum/Library/Java/JavaVirtualMachines/openjdk-22/Contents/Home" if args.lane=="floor" else "/opt/homebrew/opt/openjdk@25")
cache=Path("/Users/bbuchsbaum/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/openjfx")
fx=[cache/f"javafx-{m}"/version/f"javafx-{m}-{version}-mac-aarch64.jar" for m in ("base","graphics","controls")]
assert all(f.is_file() for f in fx),fx
module_options=["--module-path",os.pathsep.join(map(str,fx)),"--add-modules","javafx.controls","--enable-native-access=javafx.graphics"]
literal=lambda value:json.dumps(str(value))
options="Seq("+",".join(literal(x) for x in module_options)+")"
jars="Seq("+",".join("Attributed.blank(file("+literal(x)+"))" for x in fx)+")"
commands=[
    "set fx / Test / javaOptions ++= "+options,
    'set fx / Test / fullClasspath := (fx / Test / fullClasspath).value.filterNot(_.data.getName.startsWith("javafx-")) ++ '+jars,
]
if args.lane=="floor":
    commands += ["scalafmtAll","scalafmtSbt","scalafmtCheckAll","scalafmtSbtCheck","compile",
                 "fx/testOnly scaladock.fx.ThemeSuite scaladock.fx.PopoutSuite scaladock.fx.CloseAdmissionSuite",
                 "fx/packageBin","core/packageBin"]
elif args.lane=="current":
    commands += ["fx/testOnly scaladock.fx.ThemeSuite scaladock.fx.PopoutSuite scaladock.fx.CloseAdmissionSuite"]
else:
    if args.output is None: p.error("native/scene lane requires a new --output directory")
    out=args.output.resolve()
    assert not out.exists(),out
    commands += ["set fx / Test / run / fork := true",
                 "fx/Test/runMain scaladock.fx.ThemeNativeProbe "+str(out)+(" --scene-only" if args.lane=="scene" else "")]
command=["sbt","-batch",*commands]
env=dict(os.environ,JAVA_HOME=str(jdk))
result=subprocess.run(command,cwd=ROOT,env=env)
raise SystemExit(result.returncode)
