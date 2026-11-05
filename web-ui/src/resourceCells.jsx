import React from "react";
import { StatusBadge, StatusBadgeCell } from "@inductiveautomation/ignition-web-ui";

/**
 * Column cell renderers shared by every resource grid.
 *
 * These live in their own module so the pipeline page can reuse them without
 * importing the page barrel, which would create an import cycle.
 */

export const enabledCell = ({ row }) => {
  const enabled = row.originalValue.enabled;
  return (
    <StatusBadge
      type={enabled ? "success" : "default"}
      customLabel={enabled ? "ENABLED" : "DISABLED"}
    />
  );
};

export const statusCell = ({ row }) => {
  const resource = row.originalValue;
  if (!resource.enabled) {
    return <StatusBadge type="default" customLabel="STOPPED" />;
  }
  const result = resource.healthchecks?.status?.result;
  if (!result) {
    return <StatusBadge type="success" customLabel="RUNNING" />;
  }
  const error = result.details?.error;
  const stackTrace = result.details?.stackTrace;
  if (!result.healthy && (error || stackTrace)) {
    return (
      <StatusBadgeCell
        status="error"
        customLabel={result.message || "FAULTED"}
        errorDescription={error || result.message || "Resource failed"}
        errorMessage={stackTrace || error}
      />
    );
  }
  return (
    <StatusBadge
      type={result.healthy ? "success" : "error"}
      customLabel={result.message || (result.healthy ? "RUNNING" : "FAULTED")}
      showTooltip={!result.healthy}
      tooltipContent={!result.healthy ? result.message : undefined}
    />
  );
};
