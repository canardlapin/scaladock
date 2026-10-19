#!/usr/bin/env python3
"""Run the owned native S4 fixture on either recorded macOS/aarch64 runtime lane."""
import argparse, json, os, subprocess
from pathlib import Path
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('lane', choices=('floor', 'current'))
p.add_argument('output', type=Path)
p.add_argument('--tests', action='store_true', help='Run focused S4 regression suites instead of the native fixture')
p.add_argument('--qualify', action='store_true', help='Run formatting and focused tests, then the native fixture')
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
version = '24.0.1' if a.lane == 'floor' else '27'
jdk = Path.home() / 'Library/Java/JavaVirtualMachines/openjdk-22/Contents/Home' if a.lane == 'floor' else Path('/opt/homebrew/opt/openjdk@25')
cache = Path.home() / 'Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/openjfx'
jars = [cache / f'javafx-{m}/{version}/javafx-{m}-{version}-mac-aarch64.jar' for m in ('base', 'graphics', 'controls')]
assert all(j.is_file() for j in jars), jars
out = a.output.resolve()
if not a.tests:
    assert not out.exists(), out
opts = ['--module-path', os.pathsep.join(map(str, jars)), '--add-modules', 'javafx.controls', '--enable-native-access=javafx.graphics']
seq = lambda xs: 'Seq(' + ','.join(json.dumps(str(x)) for x in xs) + ')'
commands = ['set fx / Test / javaOptions ++= ' + seq(opts),
            'set fx / Test / fullClasspath := (fx / Test / fullClasspath).value.filterNot(_.data.getName.startsWith("javafx-")) ++ Seq(' + ','.join('Attributed.blank(file(' + json.dumps(str(j)) + '))' for j in jars) + ')',
            'set fx / Test / run / fork := true',
            'fx/Test/runMain scaladock.fx.AccessibilityNativeProbe ' + str(out)]
tests = 'fx/testOnly ' + ' '.join('scaladock.fx.' + s for s in (
        'AccessibilitySuite', 'KeyboardSuite', 'ChromeSuite', 'TabMenuSuite',
        'CloseAdmissionSuite', 'PresentationSuite', 'MinimizeFxSuite'))
if a.tests:
    commands = commands[:2] + ['scalafmtCheckAll', tests]
elif a.qualify:
    commands = commands[:2] + ['scalafmtCheckAll', tests] + commands[2:]
raise SystemExit(subprocess.run(['sbt', '-batch', *commands], cwd=root, env=dict(os.environ, JAVA_HOME=str(jdk))).returncode)
