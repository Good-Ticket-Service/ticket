"""지역 변환만 검증한다. 스냅샷 적재·파일 생성·DB 접속은 하지 않는다."""
import unittest

from generate import REGION_ALIASES, region_code


class RegionCodeTest(unittest.TestCase):
    def test_all_aliases_and_codes(self):
        self.assertEqual(len(REGION_ALIASES), 16)
        for code, aliases in REGION_ALIASES.items():
            for value in (code, *aliases):
                with self.subTest(value=value):
                    self.assertEqual(region_code(value), code)

    def test_unknown_values_stop_generation(self):
        for value in ("", "  ", "경", "알 수 없는 지역", "seoul"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                region_code(value)

    def test_outer_spaces(self):
        self.assertEqual(region_code(" 서울특별시 "), "SEOUL")


if __name__ == "__main__":
    unittest.main()
