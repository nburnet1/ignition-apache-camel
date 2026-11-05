import React from "react";
import { Disclosure, FormSection, Tag } from "./CamelUi";

export const libraryState = state => ({ ACTIVE: "Active", RESTART_REQUIRED: "Awaiting restart", REMOVAL_PENDING: "Removal pending", BLOCKED: "Blocked" })[state] || state;
export const libraryTone = state => ({ ACTIVE: "success", RESTART_REQUIRED: "warning", REMOVAL_PENDING: "warning", BLOCKED: "error" })[state];

export default function CamelExtensionGroups({ inventory, busy, onReview }) {
  const [query, setQuery] = React.useState("");
  const groups = inventory?.groups || [];
  const files = new Map((inventory?.files || []).map(file => [file.id, file]));
  const visible = groups.filter(group => `${group.title} ${group.schemes.join(" ")} ${group.features.join(" ")}`.toLowerCase().includes(query.toLowerCase()));
  return <FormSection title="Components & dependency groups" trailing={<Tag>{groups.length} groups</Tag>}>
    <div className="camel-group-toolbar"><p className="camel-hint">Review a component’s supporting libraries and affected routes before removing it.</p>
      <input className="camel-input" type="search" aria-label="Search extension groups" placeholder="Find a component…" value={query} onChange={event => setQuery(event.target.value)} /></div>
    {!inventory && <p className="camel-muted">Loading dependency groups…</p>}
    {inventory && !visible.length && <div className="camel-row-empty">{groups.length ? "No matching groups." : "No component groups detected. Individual libraries are listed below."}</div>}
    <div className="camel-group-grid">{visible.map(group => {
      const root = files.get(group.id);
      const dependencies = group.libraryIds.filter(id => id !== group.id).map(id => files.get(id)).filter(Boolean);
      return <article className="camel-group-card" key={group.id}>
        <div className="camel-group-heading"><div><h4>{group.title}</h4><small className="camel-muted">{group.version || root?.name}</small></div><Tag tone={libraryTone(group.state)}>{libraryState(group.state)}</Tag></div>
        <div className="camel-tags">{[...new Set([...group.schemes, ...group.features])].map(feature => <Tag key={feature}>{feature}</Tag>)}<span className="camel-muted">{root?.origin === "INCLUDED" ? "Included" : "Uploaded"}</span></div>
        {dependencies.length > 0 && <Disclosure title={`${dependencies.length} supporting ${dependencies.length === 1 ? "library" : "libraries"}`}>
          <ul className="camel-group-libraries">{dependencies.map(file => {
            const shared = (inventory.usage?.[file.id] || []).filter(title => title !== group.title);
            return <li key={file.id}><span>{file.name}</span>{shared.length > 0 && <small>Also used by {shared.join(", ")}</small>}</li>;
          })}</ul>
        </Disclosure>}
        {group.dependenciesKnown && !dependencies.length && <p className="camel-hint">No supporting libraries.</p>}
        {!group.dependenciesKnown && <p className="camel-hint">Dependency knowledge is incomplete or libraries have changed. Supporting libraries will not be removed automatically.</p>}
        {group.missing.length > 0 && <p className="camel-error">Missing declared libraries: {group.missing.join(", ")}</p>}
        <button type="button" className="camel-button camel-button--quiet" disabled={busy || group.state === "REMOVAL_PENDING"}
          aria-label={`Review removal impact for ${group.title}`} onClick={() => onReview(group.id)}>Review impact & remove</button>
      </article>;
    })}</div>
  </FormSection>;
}
