#!/usr/bin/env python3
"""Run full ScalaDock checks with a coherent, verified JavaFX test runtime.

Resolve dependencies first with `sbt update` on JDK 25. This launcher uses the
downloaded Coursier artifacts and does not change the library's compile/POM floor.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lane", choices=("floor", "current"))
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--cache", type=Path, help="Coursier v1 cache root")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    version, feature = ("24.0.1", 22) if args.lane == "floor" else ("27", 25)
    system, arch = platform.system(), platform.machine()
    classifier = {
        ("Darwin", "arm64"): "mac-aarch64", ("Darwin", "x86_64"): "mac",
        ("Linux", "aarch64"): "linux-aarch64", ("Linux", "x86_64"): "linux",
        ("Windows", "AMD64"): "win",
    }.get((system, arch))
    if classifier is None:
        parser.error(f"Unsupported native platform: {system}/{arch}")
    java_home = args.java_home.resolve()
    java_info = subprocess.check_output(
        [str(java_home / "bin/java"), "-XshowSettings:properties", "-version"],
        stderr=subprocess.STDOUT, text=True,
    )
    actual = re.search(r"java.specification.version\s*=\s*(\d+)", java_info)
    if actual is None or int(actual[1]) != feature:
        parser.error(f"{args.lane} requires JDK {feature}: {java_home}")
    caches = [args.cache] if args.cache else [
        Path.home() / "Library/Caches/Coursier/v1",
        Path.home() / ".cache/coursier/v1",
    ]
    jars = []
    for module in ("base", "graphics", "controls"):
        suffix = f"org/openjfx/javafx-{module}/{version}/javafx-{module}-{version}-{classifier}.jar"
        matches = sorted({p.resolve() for cache in caches
                          for p in cache.glob(f"https/*/maven2/{suffix}")})
        if not matches:
            parser.error(f"Missing {suffix}; run sbt update with JDK 25 first, or supply --cache")
        if len({hashlib.sha256(p.read_bytes()).hexdigest() for p in matches}) != 1:
            parser.error(f"Conflicting cached artifacts: {matches}")
        jars.append(matches[0])

    def seq(values):
        return "Seq(" + ",".join(json.dumps(str(x)) for x in values) + ")"

    options = ["--module-path", os.pathsep.join(map(str, jars)),
               "--add-modules", "javafx.controls", "--enable-native-access=javafx.graphics",
               f"-Dscaladock.expectedJavafx={version}", f"-Dscaladock.expectedJava={feature}"]
    settings = [
        "set fx / Test / javaOptions ++= " + seq(options),
        'set fx / Test / fullClasspath := (fx / Test / fullClasspath).value.filterNot(_.data.getName.startsWith("javafx-")) ++ Seq('
        + ",".join("Attributed.blank(file(" + json.dumps(str(p)) + "))" for p in jars) + ")",
    ]
    checks = ["core/compile", "fx/compile", "core/test", "fx/test"] if args.lane == "floor" else ["compile", "test"]
    command = ["sbt", "-batch", *settings, "scalafmtCheckAll", *checks]
    manifest = {
        "candidate": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "tracked_diff": subprocess.check_output(["git", "diff", "HEAD", "--name-only"], cwd=root, text=True).splitlines(),
        "lane": args.lane, "jdk_home": str(java_home), "jdk_info": java_info,
        "javafx": version, "classifier": classifier,
        "jars": [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars],
        "command": command,
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    with args.manifest.open("x") as stream:
        json.dump(manifest, stream, indent=2)
        stream.write("\n")
    print(f"QUALIFY_RUNTIME lane={args.lane} jdk={feature} javafx={version} classifier={classifier}", flush=True)
    result = subprocess.run(command, cwd=root, env=dict(os.environ, JAVA_HOME=str(java_home)))
    manifest["exit_code"] = result.returncode
    args.manifest.write_text(json.dumps(manifest, indent=2) + "\n")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
