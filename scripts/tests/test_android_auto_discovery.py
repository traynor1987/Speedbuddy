"""Fail closed if the Android Auto launcher-discovery contract is removed."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
DESCRIPTOR = ROOT / "app/src/main/res/xml/automotive_app_desc.xml"

class AndroidAutoDiscoveryTest(unittest.TestCase):
    def test_template_descriptor_and_service_are_declared(self):
        manifest = ET.parse(MANIFEST).getroot(); android = "{http://schemas.android.com/apk/res/android}"
        application = manifest.find("application"); self.assertIsNotNone(application)
        descriptor = next((item for item in application.findall("meta-data") if item.get(android + "name") == "com.google.android.gms.car.application"), None)
        self.assertIsNotNone(descriptor); self.assertEqual("@xml/automotive_app_desc", descriptor.get(android + "resource"))
        service = next((item for item in application.findall("service") if item.get(android + "name") == ".SpeedBuddyCarAppService"), None)
        self.assertIsNotNone(service); self.assertEqual("true", service.get(android + "exported"))
        intent_filter = service.find("intent-filter")
        self.assertEqual("androidx.car.app.CarAppService", intent_filter.find("action").get(android + "name"))
        self.assertEqual("androidx.car.app.category.NAVIGATION", intent_filter.find("category").get(android + "name"))
    def test_descriptor_declares_car_app_library_templates(self):
        descriptor = ET.parse(DESCRIPTOR).getroot(); self.assertEqual("automotiveApp", descriptor.tag)
        self.assertEqual(["template"], [item.get("name") for item in descriptor.findall("uses")])
if __name__ == "__main__": unittest.main()
