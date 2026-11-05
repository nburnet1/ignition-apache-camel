import React from "react";
import { Disclosure, Notice } from "./CamelUi";

export default function CamelStartupError({ failure, onDependencies }) {
  const types = failure.exceptionTypes || [];
  return <Notice error>
    <strong>{failure.title}</strong>
    <p>{failure.action}</p>
    <dl className="camel-facts camel-startup-facts">
      {failure.routeId && <div><dt>Route ID</dt><dd><code>{failure.routeId}</code></dd></div>}
      {failure.component && <div><dt>Component scheme</dt><dd><code>{failure.component}</code></dd></div>}
      {failure.reference && <div><dt>Referenced name</dt><dd><code>{failure.reference}</code></dd></div>}
      {types.length > 0 && <div><dt>Underlying exception</dt><dd><code>{types[types.length - 1]}</code></dd></div>}
    </dl>
    {(failure.code === "COMPONENT_UNAVAILABLE" || failure.code === "DEPENDENCY_UNAVAILABLE" || failure.code === "REGISTRY_OBJECT_UNAVAILABLE")
      && <button type="button" className="camel-button" onClick={onDependencies}>Review resources</button>}
    <Disclosure title="Technical details">
      <p className="camel-hint">Code: <code>{failure.code}</code></p>
      <ol className="camel-cause-chain">{types.map((type, index) => <li key={index}><code>{type}</code></li>)}</ol>
      <p className="camel-hint">Cause types run from outermost to innermost (up to 16). Exception messages and full endpoint URIs stay out of this view. Gateway logs contain the full startup exception.</p>
    </Disclosure>
  </Notice>;
}
