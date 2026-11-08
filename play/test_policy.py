"""Negatieve releasecontroles: fouten in de gebouwde bundel mogen niet stil doorgaan."""
import copy
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from check import check, A
from manifest import main


class PlayBundleTest(unittest.TestCase):
    def setUp(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'manifest.xml'
            main(f, 'nl.rene.tools.play')
            self.root = ET.parse(f).getroot()
        self.root.set('package', 'nl.rene.tools.play')
        ET.SubElement(self.root, 'uses-sdk', {A + 'targetSdkVersion': '36'})
        for e in self.root.find('application'):
            n = e.get(A + 'name', '')
            if n.startswith('.'):
                e.set(A + 'name', 'nl.rene.tools' + n)

    def validate(self, root=None, names=()):
        check(ET.tostring(self.root if root is None else root), 'nl.rene.tools.play', names)

    def test_basis(self):
        self.validate()

    def test_permission_regression(self):
        for permission in ('RECORD_AUDIO', 'READ_SMS', 'MANAGE_EXTERNAL_STORAGE', 'ACCESS_BACKGROUND_LOCATION'):
            with self.subTest(permission=permission):
                root = copy.deepcopy(self.root)
                ET.SubElement(root, 'uses-permission', {A + 'name': 'android.permission.' + permission})
                with self.assertRaises(ValueError):
                    self.validate(root)

    def test_sdk23_permission(self):
        ET.SubElement(self.root, 'uses-permission-sdk-23', {A + 'name': 'android.permission.RECORD_AUDIO'})
        with self.assertRaises(ValueError):
            self.validate()

    def test_extra_service(self):
        ET.SubElement(self.root.find('application'), 'service', {A + 'name': 'nl.rene.tools.CallRecorderService'})
        with self.assertRaises(ValueError):
            self.validate()

    def test_activity_alias(self):
        ET.SubElement(self.root.find('application'), 'activity-alias', {A + 'name': 'nl.rene.tools.HiddenActivity'})
        with self.assertRaises(ValueError):
            self.validate()

    def test_provider_collision(self):
        self.root.find('application/provider').set(A + 'authorities', 'nl.rene.tools.pdf')
        with self.assertRaises(ValueError):
            self.validate()

    def test_native_or_wrong_package(self):
        with self.assertRaises(ValueError):
            self.validate(names=['base/lib/arm64-v8a/libllama.so'])
        self.root.set('package', 'nl.rene.tools')
        with self.assertRaises(ValueError):
            self.validate()

    def test_offline_document_ocr(self):
        names = ['base/lib/' + abi + '/' + lib for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
                 for lib in ('libtesseract.so', 'libleptonica.so', 'libpngx.so', 'libjpeg.so')]
        self.validate(names=names)
        for unexpected in ('base/lib/arm64-v8a/libwhisper.so', 'base/lib/other/libtesseract.so', 'assets/libtesseract.so'):
            with self.subTest(path=unexpected), self.assertRaises(ValueError):
                self.validate(names=names + [unexpected])

    def test_local_read_aloud(self):
        names = ['base/lib/' + abi + '/' + lib for abi in ('arm64-v8a', 'x86_64')
                 for lib in ('libonnxruntime.so', 'libsherpa-onnx-jni.so')]
        self.validate(names=names)
        for unexpected in ('base/lib/armeabi-v7a/libsherpa-onnx-jni.so', 'base/lib/x86/libonnxruntime.so', 'assets/libsherpa-onnx-jni.so'):
            with self.subTest(path=unexpected), self.assertRaises(ValueError):
                self.validate(names=names + [unexpected])

    def test_old_target(self):
        self.root.find('uses-sdk').set(A + 'targetSdkVersion', '35')
        with self.assertRaises(ValueError):
            self.validate()

    def test_package_must_activate_play_policy(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(ValueError):
            main(Path(d) / 'manifest.xml', 'nl.rene.tools.player')


if __name__ == '__main__':
    unittest.main()
