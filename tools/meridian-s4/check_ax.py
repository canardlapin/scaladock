#!/usr/bin/env python3
"""Exercise only an owned synthetic probe's macOS AX tree and retain action/state pairs."""
import argparse, json, subprocess, time
from pathlib import Path
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('output', type=Path)
p.add_argument('binary', type=Path)
a = p.parse_args()
pid = a.output.joinpath('pid').read_text().strip()
def call(*args):
    return subprocess.check_output([str(a.binary), pid, *args], text=True)
def state():
    return json.loads(a.output.joinpath('state.json').read_text())
def wait_for(check):
    end = time.monotonic() + 8
    while time.monotonic() < end:
        try:
            value = state()
            if check(value): return value
        except (json.JSONDecodeError, FileNotFoundError): pass
        time.sleep(.1)
    raise AssertionError(state())
records = []
def action(name, check, command='AXPress'):
    tree = json.loads(call())
    target = next(x for x in tree if name in (x.get('AXTitle'), x.get('AXDescription')) and command in x['actions'])
    result = call(command, name).strip()
    value = wait_for(check)
    records.append(dict(name=name, command=command, enabled=target.get('AXEnabled'), result=result, state=value))
    a.output.joinpath('actions.json').write_text(json.dumps(records, indent=2))
try:
    a.output.joinpath('ax-before.json').write_text(call())
    action('Standard JavaFX button', lambda s: s['standardButtonFired']==1)
    action('Beta', lambda s: s['focused']=='Beta')
    action('Maximize', lambda s: s['maximized'])
    action('Restore layout', lambda s: not s['maximized'])
    action('Minimize', lambda s: s['minimized']==1)
    action('Restore Beta', lambda s: s['minimized']==0)
    action('Close Beta', lambda s: 'Beta' not in s['panes'])
    action('Open in new window', lambda s: s['floating']==1)
    action('Dock back into main window', lambda s: s['floating']==0)
    a.output.joinpath('ax-after.json').write_text(call())
    print('AX_ACTIONS_PASS count=' + str(len(records)))
    print('AX_ENABLED_LIMITATION count=' + str(sum(x['enabled'] is False for x in records)))
finally:
    a.output.joinpath('finish').touch()
