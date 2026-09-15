#pragma once
#include <cstddef>
#include <cstdint>

namespace photon::mgc_denoise {
// Same U12 matrix and round-to-nearest-even boundary as the former scalar
// adapter.
void SharpenRgbaToYuv(const uint8_t *rgba, size_t count, int16_t *yuv);
void SharpenRgbToRgba(const uint16_t *rgb, size_t count, uint8_t *rgba);
} // namespace photon::mgc_denoise
