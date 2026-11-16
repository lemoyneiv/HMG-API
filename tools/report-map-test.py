#!/usr/bin/env python3
"""Report import regressions, using synthetic reports only (no car files)."""
import copy, json, pathlib, subprocess, sys, tempfile, unittest

REPO = pathlib.Path(__file__).resolve().parent.parent


class ReportMapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.report = {
            'report': 'hmgapi car report 1',
            'unit': {'make': 'hyundai', 'name': 'Ioniq 6', 'model': 'CEEV',
                     'region': 'CAN', 'firmware': '260206'},
            'map_car': {
                'points': {'btsettings.projection_helper': {'class': 'example.Helper'}},
                'files': {'/system/app/BtSettings.odex': '0' * 32},
                'app_files': {'btsettings': {'package': 'example.settings',
                                            'files': ['/system/app/BtSettings.odex']}},
                'not_found': {'btsettings.projection_helper.open_wireless_page': '0 methods match'},
                'apps_absent': {},
                'by_reference': ['btsettings.projection_helper'],
            },
        }

    def run_import(self, reports=None, args=()):
        paths = []
        for i, report in enumerate(reports or [self.report]):
            path = self.root / ('report-%d.json' % i)
            path.write_text(json.dumps(report))
            paths.append(str(path))
        return subprocess.run([sys.executable, str(REPO / 'tools/report-map.py'), *paths,
                               '--maps', str(self.root / 'maps'), *args], capture_output=True, text=True)

    def car(self):
        return json.loads((self.root / 'maps/hyundai/ceev/can/260206/car.json').read_text())

    def assert_refused(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr)
        self.assertNotIn('Traceback', result.stderr)
        self.assertFalse((self.root / 'maps').exists())

    NEWER = 'not in the Car Report: newer than the HMG-API that made it'

    def assert_missing(self, missing, expected):
        """the report's own missing points, with their reasons; every other catalogue
        point the report lacks is listed as newer than the HMG-API that made it"""
        for pid, reason in expected.items():
            self.assertEqual(missing.get(pid), reason, pid)
        for pid, reason in missing.items():
            if pid not in expected:
                self.assertTrue(reason.startswith(self.NEWER), pid)

    def test_current_report_keeps_missing_points_and_hashes(self):
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        car = self.car()
        self.assert_missing(car['missing'], self.report['map_car']['not_found'])
        self.assertEqual(car['files'], self.report['map_car']['files'])
        self.assertEqual(car['by_reference'], self.report['map_car']['by_reference'])
        self.assertEqual(car['status'], 'probed')
        self.assertIsNone(car['verified'])

    def test_unknown_make_requires_explicit_override(self):
        self.report['unit'].update(make=None, name=None)
        self.assert_refused(self.run_import(), 'supply --make')
        result = self.run_import(args=['--make', 'hyundai', '--name', 'Ioniq 6'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.car()['make'], self.car()['name']), ('hyundai', 'Ioniq 6'))

    def test_legacy_missing_field_is_preserved(self):
        mc = self.report['map_car']
        mc['missing'] = mc.pop('not_found')
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_missing(self.car()['missing'], mc['missing'])

    def test_absent_app_accounts_for_all_its_catalogue_points(self):
        cat = json.loads((REPO / 'core/hookpoints/iap2.json').read_text())
        reason = 'no stock /system/app/Iap2.odex on this car'
        mc = self.report['map_car']
        mc['app_files'][cat['app']] = {'package': cat['package'], 'files': cat['files']}
        mc['apps_absent'][cat['app']] = reason
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = dict(mc['not_found'], **{p['id']: reason for p in cat['points']})
        self.assert_missing(self.car()['missing'], expected)
        self.assertEqual(json.loads((self.root / 'maps/hyundai/ceev/can/260206/iap2.json').read_text()), {})

    def test_catalogue_points_newer_than_the_report_are_listed(self):
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        missing = self.car()['missing']
        cat = json.loads((REPO / 'core/hookpoints/framework.json').read_text())
        for p in cat['points']:
            self.assertTrue(missing[p['id']].startswith(self.NEWER), p['id'])
        self.assertNotIn('btsettings.projection_helper', missing)

    def test_conflicting_found_and_missing_is_rejected(self):
        self.report['map_car']['not_found']['btsettings.projection_helper'] = 'not found'
        self.assert_refused(self.run_import(), 'both found and missing')

    def test_invalid_identity_is_rejected_before_writing(self):
        self.report['unit']['model'] = '../elsewhere'
        self.assert_refused(self.run_import(), 'invalid or missing unit.model')

    def test_missing_mapper_result_is_rejected_without_traceback(self):
        del self.report['map_car']['points']
        self.assert_refused(self.run_import(), 'map_car.points: expected an object')

    def test_multiple_reports_keep_majority_file_hash(self):
        other = copy.deepcopy(self.report)
        other['map_car']['files']['/system/app/BtSettings.odex'] = '1' * 32
        result = self.run_import([self.report, self.report, other])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.car()['files'], self.report['map_car']['files'])
        self.assertIn('outvoted: report-2.json', result.stdout)


if __name__ == '__main__':
    unittest.main()
