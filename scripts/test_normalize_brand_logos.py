"""Regression checks for audited calendar-preview logo extraction."""

import unittest

from normalize_brand_logos import REFERENCE_DERIVATIVES, audited_input


class ReferenceDerivativeSafetyTest(unittest.TestCase):
    def test_foreground_has_a_transparent_safety_margin_inside_every_reference_crop(self) -> None:
        """A preview crop must include the whole mark, not just its visible center."""
        for filename in REFERENCE_DERIVATIVES:
            with self.subTest(filename=filename):
                image, _ = audited_input(filename)
                bounds = image.getchannel("A").getbbox()
                self.assertIsNotNone(bounds)
                left, top, right, bottom = bounds
                self.assertGreaterEqual(left, 2)
                self.assertGreaterEqual(top, 2)
                self.assertGreaterEqual(image.width - right, 2)
                self.assertGreaterEqual(image.height - bottom, 2)


if __name__ == "__main__":
    unittest.main()
