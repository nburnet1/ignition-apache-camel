/** Runtime registration is authoritative; missing/stopped runtimes are not a source-code analysis. */
export function httpIngressSummary(details) {
  if (details?.state !== "RUNNING") {
    return { endpoints: [], message: "No running runtime; HTTP endpoint registrations are unavailable." };
  }
  if (!Array.isArray(details.httpEndpoints)) {
    return { endpoints: [], message: "HTTP endpoint registration details are unavailable from this Gateway." };
  }
  return {
    endpoints: details.httpEndpoints,
    message: details.httpEndpoints.length ? "" : "No HTTP endpoints registered; these permissions are inactive.",
  };
}
