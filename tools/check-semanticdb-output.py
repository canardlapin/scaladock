#!/usr/bin/env python3
"""Verify enabled, module-relative SemanticDB output after a native or imported build."""
import argparse
import hashlib
import json
from pathlib import Path


def check(root, modules, include_tests):
    module_root = root / 'modules'
    if not module_root.is_dir():
        raise ValueError('Missing modules directory')
    for path in module_root.rglob('*.semanticdb'):
        relative = path.relative_to(module_root)
        if len(relative.parts) < 3 or relative.parts[1] != 'target' or path.is_symlink():
            raise ValueError('SemanticDB output outside managed module target: ' + str(relative))
    records = []
    for name in modules:
        if not name or '/' in name or name in ('.', '..'):
            raise ValueError('Expected a module directory name')
        module = module_root / name
        sources = sorted(path for configuration in ('main', 'test')
                         if configuration == 'main' or include_tests
                         for path in (module / 'src' / configuration / 'scala').rglob('*.scala'))
        if not sources:
            raise ValueError('No Scala sources for module: ' + name)
        outputs = sorted((module / 'target').rglob('*.semanticdb'))
        for source in sources:
            suffix = 'META-INF/semanticdb/' + source.relative_to(module).as_posix() + '.semanticdb'
            matches = [path for path in outputs if path.as_posix().endswith(suffix)]
            if not matches or any(path.stat().st_size == 0 for path in matches):
                raise ValueError('Missing nonempty module-relative SemanticDB output: ' + str(source))
            records.append({'source': str(source.relative_to(root)),
                            'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                            'outputs': [{'path': str(path.relative_to(root)),
                                         'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                                        for path in matches]})
    return {'schema': 'scaladock.semanticdb-output.v1', 'root': str(root),
            'modules': modules, 'includeTests': include_tests, 'sources': records,
            'sourceAdjacentOutputs': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--modules', nargs='+', default=['core', 'fx'])
    parser.add_argument('--include-tests', action='store_true')
    args = parser.parse_args()
    print(json.dumps(check(args.root.resolve(), args.modules, args.include_tests), indent=2))


if __name__ == '__main__':
    main()
