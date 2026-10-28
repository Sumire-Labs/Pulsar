package com.sumirelabs.pulsar.compat;

import java.util.List;

/** Internal bridge implemented only on CBMultipart tiles; no optional classes in signatures. */
public interface MultipartLightTile {
    List<?> jPartList();
    boolean pulsar$needsLightPolling();
}
