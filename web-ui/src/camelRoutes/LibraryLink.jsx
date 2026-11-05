import React from "react";
import { Link } from "@inductiveautomation/ignition-gateway-lib";
import { libraryHref } from "./libraryTabs";

export default function LibraryLink({ tab, children, newTab = false, quiet = true, className }) {
  const label = children || "Open in Library";
  return <Link to={libraryHref(tab)} className={className || `camel-button${quiet ? " camel-button--quiet" : ""}`}
    {...(newTab ? { target: "_blank", rel: "noreferrer", "aria-label": `${typeof label === "string" ? label : "Open in Library"} (opens in a new tab)` } : {})}>
    {label}{newTab && <span aria-hidden="true">{" \u2197"}</span>}
  </Link>;
}
