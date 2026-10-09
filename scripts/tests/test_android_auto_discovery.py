"""Guard the phone candidate against unsupported car discovery and host bypasses."""
from pathlib import Path
import struct
import zlib
import unittest
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
MANIFEST=ROOT/"app/src/main/AndroidManifest.xml"; DESCRIPTOR=ROOT/"app/src/main/res/xml/automotive_app_desc.xml"
SERVICE=ROOT/"app/src/main/java/uk/co/traynor/speedbuddy/SpeedBuddyCarAppService.kt"
class AndroidAutoDiscoveryTest(unittest.TestCase):
    def test_phone_candidate_does_not_impersonate_navigation_or_expose_experiment(self):
        manifest=ET.parse(MANIFEST).getroot(); android="{http://schemas.android.com/apk/res/android}"; application=manifest.find("application")
        self.assertFalse(any(x.get(android+"name")=="com.google.android.gms.car.application" for x in application.findall("meta-data")))
        self.assertFalse(any(x.get(android+"name")==".SpeedBuddyCarAppService" for x in application.findall("service")))
        self.assertFalse(any("NAVIGATION" in x.get(android+"name", "") for x in manifest.findall("uses-permission")))
        self.assertIsNotNone(next(x for x in application.findall("service") if x.get(android+"name")==".DrivingService"))
    def test_retained_experiment_rejects_untrusted_hosts(self):
        source=SERVICE.read_text()
        self.assertNotIn("ALLOW_ALL_HOSTS_VALIDATOR",source)
        self.assertIn("HostValidator.Builder(this).build()",source)
    def test_limit_sign_assets_have_transparent_corners(self):
        assets=ROOT/"app/src/main/res/drawable-nodpi"
        for name in ("20","30","40","50","60","70","unknown"):
            data=(assets/f"aa_limit_{name}.png").read_bytes()
            self.assertEqual(b"\x89PNG\r\n\x1a\n",data[:8])
            offset=8; color_type=None; width=height=None; chunks=[]
            while offset < len(data):
                length=struct.unpack(">I",data[offset:offset+4])[0]; kind=data[offset+4:offset+8]
                body=data[offset+8:offset+8+length]
                if kind==b"IHDR": width,height,depth,color_type,compression,filtering,interlace=struct.unpack(">IIBBBBB",body)
                if kind==b"IDAT": chunks.append(body)
                offset += 12+length
            self.assertEqual((8,6,0,0,0),(depth,color_type,compression,filtering,interlace))
            raw=zlib.decompress(b"".join(chunks)); stride=width*4; rows=[]; prior=bytearray(stride); at=0
            for _ in range(height):
                kind=raw[at]; at+=1; row=bytearray(raw[at:at+stride]); at+=stride
                for i in range(stride):
                    left=row[i-4] if i>=4 else 0; up=prior[i]
                    if kind==1: row[i]=(row[i]+left)&255
                    elif kind==2: row[i]=(row[i]+up)&255
                    elif kind==3: row[i]=(row[i]+((left+up)//2))&255
                    elif kind==4:
                        upper_left=prior[i-4] if i>=4 else 0; p=left+up-upper_left
                        choices=(abs(p-left),left),(abs(p-up),up),(abs(p-upper_left),upper_left)
                        row[i]=(row[i]+min(choices,key=lambda choice:choice[0])[1])&255
                rows.append(row); prior=row
            self.assertEqual(0,rows[0][3],f"{name} has an opaque top-left canvas corner")
            self.assertEqual(0,rows[-1][-1],f"{name} has an opaque bottom-right canvas corner")
    def test_car_surface_is_a_passive_prompt_drivebus_projection(self):
        source=SERVICE.read_text()
        self.assertIn("DriveBus.state.collect { invalidate() }",source)
        self.assertIn("Row.IMAGE_TYPE_LARGE",source)
        self.assertIn("view.camera?.let",source)
        self.assertIn("view.upcoming?.let",source)
        self.assertNotIn("LocationManager",source)
        self.assertNotIn("requestLocationUpdates",source)
        self.assertNotIn("DrivingService(",source)
if __name__=="__main__": unittest.main()
