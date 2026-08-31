#!/usr/bin/env python3
"""Validate the actual AAB, reuse its upload key, and derive a QA APK offline."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from run_stat_bonus_probe import ROOT, JAVA, CACHE, jar

OUT = ROOT / 'docs/audits/2026-09-01-preproduction-qa'
CERT = '45:EE:A2:23:C8:3F:2A:C6:F7:98:F2:AC:54:1B:FE:A2:70:E0:DD:74:70:AE:95:D9:FC:2C:4E:AD:4A:73:FD:D2'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def run(command):
    completed = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f'Verification subprocess failed ({completed.returncode}):\n'
                           + completed.stdout[-8000:])
    return completed.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--generate-apk', action='store_true')
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    preferred = [jar('com.android.tools.build', 'bundletool', '1.18.1'),
                 jar('com.google.guava', 'guava', '33.3.1-jre'),
                 jar('com.google.protobuf', 'protobuf-java', '3.25.5')]
    remaining = sorted(str(p) for p in CACHE.glob('*/*/*/*/*.jar'))
    classpath = ':'.join(dict.fromkeys(preferred + remaining))
    command = ['/usr/bin/sandbox-exec', '-f', str(OUT / 'gradle-offline.sb'),
               str(JAVA), '-Xmx1024m', '-Djava.net.preferIPv4Stack=true',
               '-cp', classpath, 'com.android.tools.build.bundletool.BundleToolMain']
    manifest_text = run(command + ['dump', 'manifest', f'--bundle={bundle}', '--module=base'])
    config_text = run(command + ['dump', 'config', f'--bundle={bundle}'])
    validation = run(command + ['validate', f'--bundle={bundle}'])
    manifest = ET.fromstring(manifest_text)
    sdk = manifest.find('uses-sdk')
    application = manifest.find('application')
    identity = {'package': manifest.get('package'),
                'version_code': int(manifest.get(ANDROID + 'versionCode')),
                'version_name': manifest.get(ANDROID + 'versionName'),
                'min_sdk': int(sdk.get(ANDROID + 'minSdkVersion')),
                'target_sdk': int(sdk.get(ANDROID + 'targetSdkVersion')),
                'debuggable': application.get(ANDROID + 'debuggable', 'false'),
                'allow_backup': application.get(ANDROID + 'allowBackup')}
    assert identity == {'package': 'com.nullplaying', 'version_code': args.version_code,
                        'version_name': '0.4.1', 'min_sdk': 26, 'target_sdk': 36,
                        'debuggable': 'false', 'allow_backup': 'false'}, identity
    assert 'PAGE_ALIGNMENT_16K' in config_text, config_text
    signature = run([str(JAVA.parent / 'jarsigner'), '-J-Duser.language=en',
                     '-J-Duser.country=US', '-verify', str(bundle)])
    assert 'jar verified.' in signature, signature
    cert_output = run([str(JAVA.parent / 'keytool'), '-J-Duser.language=en',
                       '-J-Duser.country=US', '-printcert', '-jarfile', str(bundle)])
    fingerprints = re.findall(r'SHA256:\s*([A-F0-9:]+)', cert_output)
    assert fingerprints == [CERT], fingerprints
    (OUT / 'aab-manifest.xml').write_text(manifest_text)
    (OUT / 'aab-config.json').write_text(config_text)
    (OUT / 'aab-validation.txt').write_text(validation)
    (OUT / 'aab-signature-verification.txt').write_text(signature)
    receipt = {'identity': identity, 'certificate_sha256': CERT,
               'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(),
               'bytes': bundle.stat().st_size, 'bundletool_version': '1.18.1',
               'bundletool_validate': 'PASS', 'jar_signature': 'PASS',
               'native_page_alignment': 'PAGE_ALIGNMENT_16K'}
    if args.generate_apk:
        archive_path = ROOT / f'app/build/outputs/bundle/release/qa-release-v{args.version_code}.apks'
        assert not archive_path.exists(), 'Refusing to overwrite an existing QA APK archive'
        keystore = ROOT / 'keys/alarmquest-upload.keystore'
        password = ROOT / 'keys/alarmquest-upload.pass'
        assert keystore.is_file() and password.is_file()
        run(command + ['build-apks', f'--bundle={bundle}', f'--output={archive_path}',
                       '--mode=universal', f'--ks={keystore}', '--ks-key-alias=alarmquest-upload',
                       f'--ks-pass=file:{password}', f'--key-pass=file:{password}',
                       '--aapt2=/Users/lim/Library/Android/sdk/build-tools/36.0.0/aapt2',
                       '--max-threads=2'])
        apk = OUT / f'release-v{args.version_code}-from-aab.apk'
        with zipfile.ZipFile(archive_path) as archive:
            assert archive.testzip() is None
            apk.write_bytes(archive.read('universal.apk'))
        receipt['qa_apk'] = {'path': str(apk.relative_to(ROOT)),
                             'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                             'source': 'bundletool build-apks --mode=universal; existing upload key'}
    (OUT / 'bundle-verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
