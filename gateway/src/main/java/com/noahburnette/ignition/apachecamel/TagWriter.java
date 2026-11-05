package com.noahburnette.ignition.apachecamel;

import com.inductiveautomation.ignition.common.tags.model.TagPath;

/**
 * Writes one value to one tag. The seam keeps a tag write testable without a Gateway.
 */
@FunctionalInterface
public interface TagWriter {
    void write(TagPath tagPath, Object value) throws Exception;
}