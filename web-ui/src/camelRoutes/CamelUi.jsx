import React from "react";
import "./camel.css";

export function FormSection({ id, title, description, children, trailing }) {
  return <section id={id} className="camel-section">
    {title && <div className="camel-section-heading"><div><h3>{title}</h3>{description && <p className="camel-hint">{description}</p>}</div>{trailing}</div>}
    <div className="camel-section-body">{children}</div>
  </section>;
}

export function Disclosure({ title, summary, children, defaultOpen = false, invalid = false }) {
  const [open, setOpen] = React.useState(defaultOpen);
  React.useEffect(() => { if (invalid) setOpen(true); }, [invalid]);
  React.useEffect(() => { if (defaultOpen) setOpen(true); }, [defaultOpen]);
  return <details className="camel-disclosure" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary><span>{title}</span>{summary && <span className="camel-muted camel-disclosure-summary">{summary}</span>}</summary>
    <div className="camel-disclosure-body">{children}</div>
  </details>;
}

export function ChoiceGroup({ label, value, choices, onChange }) {
  const name = React.useId();
  return <fieldset className="camel-choices"><legend>{label}</legend>
    <div className="camel-choice-options" style={{ "--camel-choice-count": choices.length }}>{choices.map(choice => <label key={String(choice.value)} className={`camel-choice${value === choice.value ? " is-selected" : ""}`}>
      <input type="radio" name={name} value={String(choice.value)} checked={value === choice.value} onChange={() => onChange(choice.value)} />
      <span>{choice.label}</span>{choice.detail && <small>{choice.detail}</small>}
    </label>)}</div>
  </fieldset>;
}

export function Tabs({ label, tabs, value, onChange, panelIdPrefix }) {
  const id = React.useId();
  const strip = React.useRef(null);
  React.useEffect(() => {
    const reveal = () => {
      const selected = strip.current?.querySelector('[aria-selected="true"]');
      if (!selected) return;
      const parent = strip.current.getBoundingClientRect();
      const child = selected.getBoundingClientRect();
      if (child.left < parent.left) strip.current.scrollLeft -= parent.left - child.left;
      else if (child.right > parent.right) strip.current.scrollLeft += child.right - parent.right;
    };
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(strip.current);
    return () => observer.disconnect();
  }, [value]);
  return <div ref={strip} className="camel-tabs" role="tablist" aria-label={label}>{tabs.map((tab, index) => <button type="button" role="tab"
    id={`${panelIdPrefix || id}-tab-${tab.id}`} key={tab.id} aria-label={tab.invalid ? `${tab.label} — has errors` : tab.label} aria-controls={panelIdPrefix ? `${panelIdPrefix}-panel-${tab.id}` : undefined} aria-invalid={tab.invalid || undefined} aria-selected={value === tab.id} tabIndex={value === tab.id ? 0 : -1}
    onClick={() => onChange(tab.id)} onKeyDown={event => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
      onChange(tabs[next].id);
      document.getElementById(`${panelIdPrefix || id}-tab-${tabs[next].id}`)?.focus();
    }}>{tab.label}{tab.invalid && <span className="camel-tab-error" aria-hidden="true"> •</span>}{tab.count > 0 && <span className="camel-count">{tab.count}</span>}</button>)}</div>;
}

export function Notice({ children, error = false }) {
  return <div className={`camel-notice${error ? " is-error" : ""}`} role={error ? "alert" : "status"}>{children}</div>;
}

export function Tag({ children, tone }) { return <span className={`camel-tag${tone ? ` camel-tag--${tone}` : ""}`}>{children}</span>; }

export function fieldError(context, path) {
  return path.split(".").reduce((value, key) => value?.[key], context.formState?.errors);
}

export function ValidationMessage({ context, path }) {
  const error = fieldError(context, path);
  return error?.message ? <p className="camel-error" role="alert">{error.message}</p> : null;
}

export function RemoveButton({ label, onClick }) {
  return <button type="button" className="camel-icon-button" aria-label={label} title="Remove" onClick={onClick}>
    <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <path d="M4 6h12M8 3h4l1 3H7l1-3ZM6 6l1 11h6l1-11M9 9v5m2-5v5" />
    </svg>
  </button>;
}
