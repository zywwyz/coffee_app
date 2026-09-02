"""Regression checks for audited calendar-preview logo extraction."""

import io
import unittest

from PIL import Image

from scripts.normalize_brand_logos import (
    PROJECT_ROOT,
    REFERENCE_DERIVATIVES,
    audited_input,
    normalized_logo,
    remove_edge_connected_pale_background,
)


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


class ApprovedMannerNormalizationTest(unittest.TestCase):
    def test_manner_output_preserves_approved_foreground_aspect_and_safe_border(self) -> None:
        source = remove_edge_connected_pale_background(
            Image.open(PROJECT_ROOT / "assets/brand-logos/reference/manner-user-approved.jpeg"),
        )
        manner_input, input_kind = audited_input("brand_logo_manner.png")
        self.assertEqual("user-approved MANNER JPEG", input_kind)
        output = normalized_logo(manner_input, 430)
        source_bounds = source.getchannel("A").getbbox()
        output_bounds = output.getchannel("A").getbbox()

        self.assertEqual((512, 512), output.size)
        self.assertIsNotNone(source_bounds)
        self.assertIsNotNone(output_bounds)
        source_left, source_top, source_right, source_bottom = source_bounds
        output_left, output_top, output_right, output_bottom = output_bounds
        self.assertAlmostEqual(
            (source_right - source_left) / (source_bottom - source_top),
            (output_right - output_left) / (output_bottom - output_top),
            delta=(source_right - source_left) / (source_bottom - source_top) * 0.01,
        )
        for edge in range(2):
            self.assertEqual((0, 0), output.getchannel("A").crop((edge, 0, edge + 1, 512)).getextrema())
            self.assertEqual((0, 0), output.getchannel("A").crop((511 - edge, 0, 512 - edge, 512)).getextrema())
            self.assertEqual((0, 0), output.getchannel("A").crop((0, edge, 512, edge + 1)).getextrema())
            self.assertEqual((0, 0), output.getchannel("A").crop((0, 511 - edge, 512, 512 - edge)).getextrema())

    def test_manner_retains_top_word_box_and_lower_triangle(self) -> None:
        output = normalized_logo(audited_input("brand_logo_manner.png")[0], 430)
        bounds = output.getchannel("A").getbbox()
        self.assertIsNotNone(bounds)
        left, top, right, bottom = bounds
        alpha = output.getchannel("A")
        for start, end, name in ((0.0, 0.2, "top triangle"), (0.35, 0.65, "word box"), (0.8, 1.0, "lower triangle")):
            y0 = top + round((bottom - top) * start)
            y1 = top + round((bottom - top) * end)
            self.assertIsNotNone(alpha.crop((left, y0, right, max(y0 + 1, y1))).getbbox(), name)

    def test_manner_normalization_is_idempotent(self) -> None:
        source = remove_edge_connected_pale_background(
            Image.open(PROJECT_ROOT / "assets/brand-logos/reference/manner-user-approved.jpeg"),
        )
        first = normalized_logo(source, 430)
        second = normalized_logo(source, 430)
        first_bytes = io.BytesIO()
        second_bytes = io.BytesIO()
        first.save(first_bytes, format="PNG", optimize=False)
        second.save(second_bytes, format="PNG", optimize=False)
        self.assertEqual(first_bytes.getvalue(), second_bytes.getvalue())


if __name__ == "__main__":
    unittest.main()
