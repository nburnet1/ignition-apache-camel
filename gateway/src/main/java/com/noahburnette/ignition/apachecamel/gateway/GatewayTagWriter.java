package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.model.SecurityContext;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import com.noahburnette.ignition.apachecamel.TagWriter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class GatewayTagWriter implements TagWriter {
    private static final long WRITE_TIMEOUT_MILLIS = 30_000L;

    private final GatewayTagManager tagManager;

    GatewayTagWriter(GatewayContext context) {
        this.tagManager = Objects.requireNonNull(context, "context").getTagManager();
    }

    @Override
    public void write(TagPath tagPath, Object value) throws Exception {
        List<QualityCode> qualities;
        try {
            qualities = tagManager
                .writeAsync(List.of(tagPath), List.of(value), SecurityContext.systemContext())
                .get(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted writing tag " + tagPath.toStringFull(), error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException(
                "could not write tag " + tagPath.toStringFull() + ": " + cause.getMessage(), cause
            );
        } catch (TimeoutException error) {
            throw new IllegalStateException("timed out writing tag " + tagPath.toStringFull(), error);
        }
        if (qualities == null || qualities.size() != 1) {
            throw new IllegalStateException(
                "tag write for " + tagPath.toStringFull() + " returned no quality"
            );
        }
        QualityCode quality = qualities.get(0);
        if (quality == null || quality.isNotGood()) {
            throw new IllegalStateException(
                "tag write for " + tagPath.toStringFull() + " returned quality " + quality
            );
        }
    }
}