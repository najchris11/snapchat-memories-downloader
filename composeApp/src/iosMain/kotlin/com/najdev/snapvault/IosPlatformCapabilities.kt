package com.najdev.snapvault

// IosMediaProcessor writes embedded photo metadata with ImageIO. Videos receive only a
// filesystem modification date, not embedded date/location metadata. On develop,
// IosZipPipelineRunner.combineAll composites photos, while video combining still returns false.
actual val platformMediaCapabilities = iosMediaCapabilities
